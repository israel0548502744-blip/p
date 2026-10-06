package com.blueshield.app.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.GLES20
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max

/**
 * Render pass: MediaCodec decode → SurfaceTexture → GL censor shader → hardware H.264
 * encoder (input Surface) → MediaMuxer (MP4). Original resolution, original frame
 * timestamps (so the original frame rate, including VFR), rotation kept as metadata,
 * original audio passed through (or transcoded to AAC when MP4 can't hold it).
 */
class Renderer(private val context: Context, private val meta: VideoMeta) {

    interface FrameSource {
        /** Feathered 8-bit mask for the [index]-th decoded frame, shown at [ptsUs] (display orientation), or null when nothing is censored. */
        fun mask(index: Int, ptsUs: Long): Mask?
    }

    class Mask(val width: Int, val height: Int, val data: ByteArray)

    class Options(val rgb: Int, val animated: Boolean, val keepAudio: Boolean, val quality: String)

    /**
     * @param shouldContinue polled between frames; return false to cancel (blocks while paused).
     * @param onProgress frame index done.
     * @param onPreview occasionally receives a small preview bitmap of the output.
     */
    fun render(
        out: File, source: FrameSource, opts: Options, totalFrames: Int,
        shouldContinue: () -> Boolean, onProgress: (Int) -> Unit, onPreview: (Bitmap) -> Unit,
    ) {
        val audioTmp = if (opts.keepAudio && meta.hasAudio) prepareAudio() else null
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxer.setOrientationHint(meta.rotation)
        // The encoder may need a slightly different size than the video (alignment, minimum size): the frame is
        // drawn to fill it, so a 200×112 clip comes out e.g. 208×112 (or scaled up) instead of failing to start.
        val enc = EncoderPicker.open(meta.codedWidth, meta.codedHeight, meta.fps) { ew, eh -> bitrate(ew, eh, meta.fps, opts.quality) }
        val encoder = enc.codec
        val inputSurface = enc.surface
        val w = enc.width
        val h = enc.height
        val egl = EglSurface(inputSurface)
        val shader = CensorShader(meta.rotation)
        val st = SurfaceTexture(shader.videoTex)
        val decoderSurface = Surface(st)
        val frameLock = ReentrantLock()
        val frameCond = frameLock.newCondition()
        var frameReady = false
        st.setOnFrameAvailableListener {
            frameLock.withLock {
                frameReady = true
                frameCond.signalAll()
            }
        }
        val ex = MediaExtractor()
        ex.setDataSource(context, meta.uri, null)
        val vTrack = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        ex.selectTrack(vTrack)
        val vFormat = ex.getTrackFormat(vTrack)
        // Decode in *coded* orientation: rotation is written once, as MP4 metadata (setOrientationHint).
        // Leaving rotation-degrees in the format would make the decoder rotate the frames as well.
        if (vFormat.containsKey(MediaFormat.KEY_ROTATION)) vFormat.setInteger(MediaFormat.KEY_ROTATION, 0)
        val decoder = MediaCodec.createDecoderByType(vFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(vFormat, decoderSurface, null, 0)
        decoder.start()

        var videoTrackOut = -1
        var audioTrackOut = -1
        var muxerStarted = false
        val audioEx = audioTmp?.let { f -> MediaExtractor().apply { setDataSource(f.path); selectTrack(0) } }
        val encInfo = MediaCodec.BufferInfo()

        fun startMuxerIfReady() {
            if (!muxerStarted && videoTrackOut >= 0) {
                if (audioEx != null) audioTrackOut = muxer.addTrack(audioEx.getTrackFormat(0))
                muxer.start()
                muxerStarted = true
            }
        }

        fun drainEncoder(endOfStream: Boolean) {
            if (endOfStream) encoder.signalEndOfInputStream()
            while (true) {
                val idx = encoder.dequeueOutputBuffer(encInfo, if (endOfStream) 10_000 else 0)
                when {
                    idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                    idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrackOut = muxer.addTrack(encoder.outputFormat)
                        startMuxerIfReady()
                    }
                    idx >= 0 -> {
                        val buf = encoder.getOutputBuffer(idx)!!
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encInfo.size = 0
                        if (encInfo.size > 0 && muxerStarted) {
                            buf.position(encInfo.offset)
                            buf.limit(encInfo.offset + encInfo.size)
                            muxer.writeSampleData(videoTrackOut, buf, encInfo)
                        }
                        encoder.releaseOutputBuffer(idx, false)
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }

        val decInfo = MediaCodec.BufferInfo()
        val st4 = FloatArray(16)
        var inputDone = false
        var index = 0
        var lastPreview = 0L
        var cancelled = false
        try {
            loop@ while (true) {
                if (!inputDone) {
                    val inIdx = decoder.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = decoder.getInputBuffer(inIdx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = decoder.dequeueOutputBuffer(decInfo, 10_000)
                if (outIdx < 0) continue
                val eos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val render = decInfo.size > 0
                decoder.releaseOutputBuffer(outIdx, render)
                if (render) {
                    frameLock.withLock {
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                        while (!frameReady) {
                            val left = deadline - System.nanoTime()
                            check(left > 0) { "Timed out waiting for a decoded frame" }
                            frameCond.awaitNanos(left)
                        }
                        frameReady = false
                    }
                    st.updateTexImage()
                    st.getTransformMatrix(st4)
                    if (!shouldContinue()) {
                        cancelled = true
                        break@loop
                    }
                    val m = source.mask(index, decInfo.presentationTimeUs)
                    if (m != null) shader.uploadMask(m.width, m.height, m.data) else shader.uploadMask(1, 1, ZERO)
                    shader.draw(w, h, st4, opts.rgb, (decInfo.presentationTimeUs / 1e6).toFloat(), opts.animated)
                    val now = System.nanoTime()
                    if (now - lastPreview > 700_000_000L) {
                        lastPreview = now
                        onPreview(readPreview(w, h))
                    }
                    egl.setPresentationTime(decInfo.presentationTimeUs * 1000)
                    egl.swap()
                    drainEncoder(false)
                    index++
                    onProgress(index)
                }
                if (eos) break
            }
            if (!cancelled) {
                drainEncoder(true)
                startMuxerIfReady()
                if (audioEx != null && audioTrackOut >= 0) copySamples(audioEx, muxer, audioTrackOut)
            }
        } finally {
            runCatching { decoder.stop() }
            decoder.release()
            ex.release()
            runCatching { encoder.stop() }
            encoder.release()
            shader.release()
            st.release()
            decoderSurface.release()
            egl.close()
            inputSurface.release()
            audioEx?.release()
            audioTmp?.delete()
            if (muxerStarted) runCatching { muxer.stop() }
            muxer.release()
            if (cancelled) out.delete()
        }
        if (cancelled) throw CancelledException()
    }

    class CancelledException : RuntimeException("Cancelled")

    /** Small, upright-agnostic preview of what was just drawn (read back from the GL framebuffer). */
    private fun readPreview(w: Int, h: Int): Bitmap {
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buf.rewind()
        full.copyPixelsFromBuffer(buf)
        val s = 480f / max(w, h)
        val matrix = android.graphics.Matrix().apply {
            postScale(s, -s) // GL rows are bottom-up
            postRotate(meta.rotation.toFloat())
        }
        val out = Bitmap.createBitmap(full, 0, 0, w, h, matrix, true)
        full.recycle()
        return out
    }

    /** Copies (or transcodes to AAC) the original audio into a temporary MP4 the muxer can read. */
    private fun prepareAudio(): File? {
        val tmp = File.createTempFile("audio", ".m4a", context.cacheDir)
        return runCatching {
            val ex = MediaExtractor()
            ex.setDataSource(context, meta.uri, null)
            val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ex.selectTrack(track)
            val format = ex.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val mux = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                if (mime == MediaFormat.MIMETYPE_AUDIO_AAC || mime == MediaFormat.MIMETYPE_AUDIO_AMR_NB || mime == MediaFormat.MIMETYPE_AUDIO_AMR_WB) {
                    val t = mux.addTrack(format)
                    mux.start()
                    copySamples(ex, mux, t)
                } else {
                    AudioTranscoder.toAac(ex, format, mux)
                }
                mux.stop()
            } finally {
                mux.release()
                ex.release()
            }
            tmp
        }.getOrElse {
            tmp.delete()
            null // the video is still produced, just without audio
        }
    }

    private fun copySamples(ex: MediaExtractor, mux: MediaMuxer, track: Int) {
        val buf = ByteBuffer.allocate(1 shl 20)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val n = ex.readSampleData(buf, 0)
            if (n < 0) break
            info.set(0, n, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            mux.writeSampleData(track, buf, info)
            ex.advance()
        }
    }

    companion object {
        private val ZERO = ByteArray(1)

        fun bitrate(w: Int, h: Int, fps: Double, quality: String): Int {
            val bpp = when (quality) { "high" -> 0.20; "small" -> 0.07; else -> 0.12 }
            return (w * h * fps.coerceIn(15.0, 60.0) * bpp).toInt().coerceIn(1_000_000, 60_000_000)
        }
    }
}

/** Decodes any audio MediaCodec supports and re-encodes it as AAC-LC (for MP4 output). */
object AudioTranscoder {
    fun toAac(ex: MediaExtractor, inFormat: MediaFormat, mux: MediaMuxer) {
        val decoder = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
        decoder.configure(inFormat, null, null, 0)
        decoder.start()
        val rate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val outFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 1 shl 16)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        encoder.configure(outFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()
        val info = MediaCodec.BufferInfo()
        var track = -1
        var extractDone = false
        var decodeDone = false
        var pending: ByteBuffer? = null
        var pendingPts = 0L
        try {
            while (true) {
                if (!extractDone) {
                    val i = decoder.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = ex.readSampleData(decoder.getInputBuffer(i)!!, 0)
                        if (n < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); extractDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, n, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                if (pending == null && !decodeDone) {
                    val o = decoder.dequeueOutputBuffer(info, 10_000)
                    if (o >= 0) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) decodeDone = true
                        val src = decoder.getOutputBuffer(o)!!
                        src.position(info.offset); src.limit(info.offset + info.size)
                        pending = ByteBuffer.allocate(info.size).put(src).also { it.flip() }
                        pendingPts = info.presentationTimeUs
                        decoder.releaseOutputBuffer(o, false)
                    }
                }
                val p = pending
                if (p != null || decodeDone) {
                    val i = encoder.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val dst = encoder.getInputBuffer(i)!!
                        if (p != null) {
                            val n = minOf(dst.remaining(), p.remaining())
                            val slice = p.duplicate().apply { limit(position() + n) }
                            dst.put(slice)
                            p.position(p.position() + n)
                            encoder.queueInputBuffer(i, 0, n, pendingPts, 0)
                            if (!p.hasRemaining()) pending = null
                        } else {
                            encoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        }
                    }
                }
                val e = encoder.dequeueOutputBuffer(info, 10_000)
                if (e == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(encoder.outputFormat); mux.start()
                } else if (e >= 0) {
                    val b = encoder.getOutputBuffer(e)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                        b.position(info.offset); b.limit(info.offset + info.size)
                        mux.writeSampleData(track, b, info)
                    }
                    encoder.releaseOutputBuffer(e, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            runCatching { decoder.stop() }; decoder.release()
            runCatching { encoder.stop() }; encoder.release()
        }
    }
}
