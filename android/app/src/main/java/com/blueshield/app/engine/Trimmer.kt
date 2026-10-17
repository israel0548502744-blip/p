package com.blueshield.app.engine

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.nio.ByteBuffer

/**
 * Cuts the part of a video to censor ([startSec]–[endSec]) into a new MP4 by copying the compressed samples (no
 * re-encoding: fast, and the picture is untouched). The cut starts at the last key frame at or before [startSec],
 * since a video can only be decoded from a key frame; timestamps are shifted so the clip starts at 0.
 */
object Trimmer {
    fun trim(context: Context, meta: VideoMeta, startSec: Double, endSec: Double): Uri {
        val out = File(context.cacheDir, "trim_${System.currentTimeMillis()}.mp4")
        val startUs = (startSec * 1e6).toLong()
        val endUs = (endSec * 1e6).toLong()
        val video = MediaExtractor().apply { setDataSource(context, meta.uri, null) }
        val audio = MediaExtractor().apply { setDataSource(context, meta.uri, null) }
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val vTrack = (0 until video.trackCount).first { video.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            video.selectTrack(vTrack)
            val vOut = muxer.addTrack(video.getTrackFormat(vTrack))
            val aTrack = (0 until audio.trackCount).firstOrNull { audio.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            // an audio format MP4 can't hold is left out (the censoring would drop it the same way)
            val aOut = aTrack?.let { t -> runCatching { muxer.addTrack(audio.getTrackFormat(t)) }.getOrNull()?.also { audio.selectTrack(t) } }
            muxer.setOrientationHint(meta.rotation)
            muxer.start()
            video.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val base = video.sampleTime.coerceAtLeast(0)
            val buf = ByteBuffer.allocate(8 shl 20)
            val info = MediaCodec.BufferInfo()
            fun copy(ex: MediaExtractor, track: Int) {
                while (true) {
                    val n = ex.readSampleData(buf, 0)
                    if (n < 0 || ex.sampleTime > endUs) break
                    if (ex.sampleTime >= base) {
                        val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                        info.set(0, n, ex.sampleTime - base, flags)
                        muxer.writeSampleData(track, buf, info)
                    }
                    ex.advance()
                }
            }
            copy(video, vOut)
            if (aOut != null) {
                audio.seekTo(base, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                copy(audio, aOut)
            }
            muxer.stop()
        } catch (t: Throwable) {
            out.delete()
            throw t
        } finally {
            runCatching { muxer.release() }
            video.release()
            audio.release()
        }
        return Uri.fromFile(out)
    }
}
