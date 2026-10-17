package com.blueshield.app.engine

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface

/**
 * Offline work, not playback: without being told, a hardware codec clocks itself for real-time speed (30 frames a
 * second for a 30 fps clip). "Non-real-time priority, as fast as possible" (as Media3's Transformer asks) lets it
 * run at full speed. Some devices refuse these keys; then the codec is configured again without them.
 */
object Codecs {
    fun configureFast(codec: MediaCodec, format: MediaFormat, surface: Surface?, flags: Int) {
        try {
            format.setInteger(MediaFormat.KEY_PRIORITY, 1)
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
            codec.configure(format, surface, null, flags)
        } catch (e: Exception) {
            Breadcrumbs.mark("codec: no fast mode on ${runCatching { codec.name }.getOrDefault("?")} (${e.javaClass.simpleName})")
            codec.reset()
            format.removeKey(MediaFormat.KEY_PRIORITY)
            format.removeKey(MediaFormat.KEY_OPERATING_RATE)
            codec.configure(format, surface, null, flags)
        }
    }
}
