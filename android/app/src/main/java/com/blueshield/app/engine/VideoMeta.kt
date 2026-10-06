package com.blueshield.app.engine

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

/** What we know about the selected video (display size is after rotation). */
data class VideoMeta(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val durationUs: Long,
    val codedWidth: Int,
    val codedHeight: Int,
    val rotation: Int,
    val fps: Double,
    val frameCount: Int,
    val videoMime: String,
    val audioMime: String?,
) {
    val width get() = if (rotation % 180 == 0) codedWidth else codedHeight
    val height get() = if (rotation % 180 == 0) codedHeight else codedWidth
    val hasAudio get() = audioMime != null
    val durationSec get() = durationUs / 1e6

    companion object {
        fun probe(context: Context, uri: Uri): VideoMeta {
            var name = "video.mp4"
            var size = 0L
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            }
            if (size == 0L && uri.scheme == "file") {
                uri.path?.let { java.io.File(it).let { f -> name = f.name; size = f.length() } }
            }
            val ex = MediaExtractor()
            try {
                ex.setDataSource(context, uri, null)
                var video: MediaFormat? = null
                var audioMime: String? = null
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") && video == null) video = f
                    if (mime.startsWith("audio/") && audioMime == null) audioMime = mime
                }
                val v = video ?: throw IllegalArgumentException("בקובץ הזה אין ערוץ וידאו.")
                val mmr = MediaMetadataRetriever()
                var rotation = 0
                var frames = 0
                var durationUs = if (v.containsKey(MediaFormat.KEY_DURATION)) v.getLong(MediaFormat.KEY_DURATION) else 0L
                try {
                    mmr.setDataSource(context, uri)
                    rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (durationUs <= 0) durationUs = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
                    if (android.os.Build.VERSION.SDK_INT >= 28) {
                        frames = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
                    }
                } finally {
                    mmr.release()
                }
                if (rotation == 0 && v.containsKey(MediaFormat.KEY_ROTATION)) rotation = v.getInteger(MediaFormat.KEY_ROTATION)
                var fps = if (v.containsKey(MediaFormat.KEY_FRAME_RATE)) runCatching { v.getInteger(MediaFormat.KEY_FRAME_RATE).toDouble() }
                    .getOrElse { v.getFloat(MediaFormat.KEY_FRAME_RATE).toDouble() } else 0.0
                if (fps <= 0 && frames > 0 && durationUs > 0) fps = frames / (durationUs / 1e6)
                if (fps <= 0 || fps > 240) fps = 30.0
                if (frames <= 0) frames = maxOf(1, Math.round(durationUs / 1e6 * fps).toInt())
                return VideoMeta(
                    uri, name, size, durationUs,
                    v.getInteger(MediaFormat.KEY_WIDTH), v.getInteger(MediaFormat.KEY_HEIGHT),
                    ((rotation % 360) + 360) % 360, fps, frames,
                    v.getString(MediaFormat.KEY_MIME) ?: "video/?", audioMime,
                )
            } finally {
                ex.release()
            }
        }
    }
}
