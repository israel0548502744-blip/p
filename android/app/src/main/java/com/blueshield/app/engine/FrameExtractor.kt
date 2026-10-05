package com.blueshield.app.engine

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import com.blueshield.core.image.Orientation
import com.blueshield.core.image.RgbImage

/**
 * Hardware-decodes the video with MediaCodec (ByteBuffer/YUV output, never the whole
 * video in memory) and converts each frame to an upright RGB image at analysis size —
 * the YUV→RGB conversion samples only the pixels it needs, so it's cheap even for 4K.
 */
class FrameExtractor(private val context: Context, private val meta: VideoMeta, private val outW: Int, private val outH: Int) {

    /** Calls [onFrame] for every decoded frame in presentation order; return false to stop. */
    fun run(onFrame: (index: Int, ptsUs: Long, frame: RgbImage) -> Boolean) {
        val ex = MediaExtractor()
        ex.setDataSource(context, meta.uri, null)
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var index = 0
        try {
            while (true) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(10_000)
                    if (inIdx >= 0) {
                        val buf = codec.getInputBuffer(inIdx)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIdx, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                if (outIdx >= 0) {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    var keepGoing = true
                    if (info.size > 0) {
                        val image = codec.getOutputImage(outIdx)
                        if (image != null) {
                            val rgb = image.use { toRgb(it) }
                            keepGoing = onFrame(index++, info.presentationTimeUs, rgb)
                        }
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                    if (eos || !keepGoing) break
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            ex.release()
        }
    }

    /** YUV_420_888 → upright RGB at outW × outH (nearest-neighbour sampling of the crop rect). */
    private fun toRgb(img: Image): RgbImage {
        require(img.format == ImageFormat.YUV_420_888) { "Unexpected decoder image format ${img.format}" }
        val crop = img.cropRect
        val cw = crop.width()
        val ch = crop.height()
        val y = img.planes[0]
        val u = img.planes[1]
        val v = img.planes[2]
        val yb = y.buffer
        val ub = u.buffer
        val vb = v.buffer
        val out = RgbImage(outW, outH)
        val d = out.data
        val rot = meta.rotation
        // display (upright) size
        val dw = if (rot % 180 == 0) cw else ch
        val dh = if (rot % 180 == 0) ch else cw
        for (oy in 0 until outH) {
            val dy = (oy + 0.5f) * dh / outH
            for (ox in 0 until outW) {
                val dx = (ox + 0.5f) * dw / outW
                val (cx, cy) = Orientation.displayToCoded(dx.toInt(), dy.toInt(), rot, cw, ch)
                val px = (cx.coerceIn(0, cw - 1) + crop.left)
                val py = (cy.coerceIn(0, ch - 1) + crop.top)
                val yy = (yb.get(py * y.rowStride + px * y.pixelStride).toInt() and 0xFF) - 16
                val uvx = px / 2
                val uvy = py / 2
                val uu = (ub.get(uvy * u.rowStride + uvx * u.pixelStride).toInt() and 0xFF) - 128
                val vv = (vb.get(uvy * v.rowStride + uvx * v.pixelStride).toInt() and 0xFF) - 128
                val c = 1.164f * yy
                val o = (oy * outW + ox) * 3
                d[o] = (c + 1.596f * vv).toInt().coerceIn(0, 255).toByte()
                d[o + 1] = (c - 0.392f * uu - 0.813f * vv).toInt().coerceIn(0, 255).toByte()
                d[o + 2] = (c + 2.017f * uu).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }
}
