package com.blueshield.app.engine

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Opens an H.264 encoder (Surface input) for a [width]×[height] video. Hardware encoders refuse sizes
 * outside their range or alignment: e.g. a 200×112 clip fails in `MediaCodec.start()` on many phones
 * (too small, width not a multiple of 16). So every AVC encoder is asked for its capabilities, the size
 * is aligned (and scaled up, keeping the aspect ratio, when below the minimum), and each candidate is
 * actually started; the first one that starts wins. Software encoders are the last resort.
 */
object EncoderPicker {
    class Opened(val codec: MediaCodec, val surface: Surface, val width: Int, val height: Int, val name: String)

    private const val TAG = "EncoderPicker"
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC

    fun open(width: Int, height: Int, fps: Double, bitrate: (Int, Int) -> Int): Opened {
        val errors = ArrayList<String>()
        for ((name, w, h, range) in candidates(width, height)) {
            val codec = runCatching { MediaCodec.createByCodecName(name) }.getOrNull() ?: continue
            try {
                val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, bitrate(w, h).coerceIn(range.first, range.last))
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps.roundToInt().coerceAtLeast(1))
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val surface = codec.createInputSurface()
                try {
                    codec.start()
                } catch (e: Exception) {
                    surface.release()
                    throw e
                }
                if (w != width || h != height) Log.i(TAG, "$name encodes ${width}x$height as ${w}x$h")
                return Opened(codec, surface, w, h, name)
            } catch (e: Exception) {
                errors += "$name ${w}x$h: ${e.javaClass.simpleName}"
                Log.w(TAG, "encoder $name failed at ${w}x$h", e)
                codec.release()
            }
        }
        throw IllegalStateException("אין במכשיר מקודד H.264 שתומך בסרטון בגודל ${width}x$height (${errors.joinToString("; ")})")
    }

    private data class Candidate(val name: String, val width: Int, val height: Int, val bitrates: IntRange)

    /** Encoder × size attempts, best first: hardware before software, exact size before 16-aligned. */
    private fun candidates(width: Int, height: Int): List<Candidate> {
        val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) } }
            .sortedBy { if (isSoftware(it)) 1 else 0 } // stable: keeps the platform's preference order
        val out = ArrayList<Candidate>()
        for (info in infos) {
            val caps = runCatching { info.getCapabilitiesForType(MIME).videoCapabilities }.getOrNull() ?: continue
            val bitrates = caps.bitrateRange.let { it.lower..it.upper }
            val sizes = LinkedHashSet<Pair<Int, Int>>()
            fit(width, height, max(2, caps.widthAlignment), max(2, caps.heightAlignment), caps)?.let { sizes += it }
            // Some hardware encoders advertise alignment 2 but only really work on 16-pixel blocks.
            fit(width, height, 16, 16, caps)?.let { sizes += it }
            for ((w, h) in sizes) out += Candidate(info.name, w, h, bitrates)
        }
        if (out.isEmpty()) {
            // No capability info: let the platform pick, at an even and a 16-aligned size.
            val name = runCatching { MediaCodec.createEncoderByType(MIME).let { c -> c.name.also { c.release() } } }.getOrNull()
            if (name != null) {
                out += Candidate(name, alignUp(width, 2), alignUp(height, 2), 1..Int.MAX_VALUE)
                out += Candidate(name, alignUp(max(width, 128), 16), alignUp(max(height, 128), 16), 1..Int.MAX_VALUE)
            }
        }
        return out
    }

    /** The size closest to [w]×[h] (same aspect) the encoder accepts with the given alignment, or null. */
    private fun fit(w: Int, h: Int, wa: Int, ha: Int, caps: MediaCodecInfo.VideoCapabilities): Pair<Int, Int>? {
        val minW = caps.supportedWidths.lower
        val minH = caps.supportedHeights.lower
        var s = max(1.0, max(minW.toDouble() / w, minH.toDouble() / h))
        repeat(12) {
            val ew = alignUp((w * s).roundToInt(), wa)
            val eh = alignUp((h * s).roundToInt(), ha)
            if (caps.isSizeSupported(ew, eh)) return ew to eh
            // Too large for the encoder (e.g. 8K on a 4K encoder): shrink; otherwise grow towards its minimum.
            val tooBig = ew > caps.supportedWidths.upper || eh > caps.supportedHeights.upper ||
                runCatching { !caps.getSupportedHeightsFor(ew).contains(eh) && eh > caps.getSupportedHeightsFor(ew).upper }.getOrDefault(false)
            s *= if (tooBig) 0.85 else 1.15
        }
        return null
    }

    private fun isSoftware(info: MediaCodecInfo): Boolean =
        if (Build.VERSION.SDK_INT >= 29) info.isSoftwareOnly
        else info.name.startsWith("OMX.google.") || info.name.startsWith("c2.android.")

    private fun alignUp(v: Int, a: Int) = (v + a - 1) / a * a
}
