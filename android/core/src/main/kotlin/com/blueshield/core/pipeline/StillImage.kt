package com.blueshield.core.pipeline

import com.blueshield.core.CensorSettings
import com.blueshield.core.PipelineSpec
import com.blueshield.core.image.ByteMask
import com.blueshield.core.image.EdgeSnap
import com.blueshield.core.image.MaskOps
import com.blueshield.core.image.RgbImage
import com.blueshield.core.image.TextGuard
import com.blueshield.core.ml.ModelStore
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Photos: the same analysis as a video, on a single frame. A photo can't accumulate face observations over
 * time, so one good look at a face is enough for a gender / age decision (the confidence threshold still
 * applies); everything else — people, ownership, neckline, text protection — is identical.
 */
object StillImage {
    fun analyze(
        models: ModelStore, settings: CensorSettings, spec: PipelineSpec, img: RgbImage, maskW: Int, maskH: Int, store: File,
        /** Called with the analyzer once the photo is analysed (debugging tools read its skin probability). */
        inspect: (Analyzer) -> Unit = {},
    ): Analysis {
        val still = spec.copy(gender = spec.gender.copy(voteFactor = 1.0, minVotes = 1, minWeight = 0.3, minAgeVotes = 1))
        val analyzer = Analyzer(models, settings.copy(speed = "quality"), still, img.width, img.height, 1.0, maskW, maskH, store, spec.personMasks.photoSize)
        analyzer.process(listOf(img)) { inspect(analyzer) }
        return analyzer.finish()
    }

    /**
     * Alpha (0..255) at [outW] × [outH] for the current decisions. With the photo's [pixels] (ARGB, outW × outH)
     * the mask is first fitted to the photo's own edges ([EdgeSnap]) and only lightly feathered, so the censor
     * follows the outline of an arm instead of a blob around it. The safety margin grows only along smooth colour
     * ([EdgeSnap.growAlongColour]): it takes back a shaded rim of skin, but stops at the arm's outline — a blind
     * dilation was half of all the paint off the skin (a 2–3 px halo around every arm and finger). The feather is
     * centred on the grown edge: alpha is at least 0.5 everywhere on it, the softness setting sets its width.
     */
    fun alpha(a: Analysis, settings: CensorSettings, decisions: Map<Int, Boolean>, outW: Int, outH: Int, pixels: IntArray? = null): ByteMask? {
        val raw = a.maskFor(0, decisions, settings, 0)
        if (!raw.any()) return null
        if (pixels == null) return Composer.feather(raw, outW, outH, settings.softness, settings.aggressive).resize(outW, outH)
        // fitted, grown and feathered at the edge-fitting size (at most 1600 px), then scaled up once: a 12 MP
        // photo processed at full size needed ~30 bytes per pixel and the phone killed the app for memory
        val snapped = EdgeSnap.snap(raw, pixels, outW, outH, fullSize = false)
        val w = snapped.width
        val h = snapped.height
        val diag = hypot(w.toFloat(), h.toFloat())
        val featherPx = settings.softness / 100f * 0.006f * diag
        val grow = featherPx * 0.6f + (if (settings.aggressive) 0.004f else 0.001f) * diag
        val bin = ByteMask(w, h, ByteArray(w * h) { if ((snapped.data[it].toInt() and 0xFF) > 127) -1 else 0 })
        val px = if (w == outW && h == outH) pixels else EdgeSnap.downscale(pixels, outW, outH, w, h)
        val grown = EdgeSnap.growAlongColour(bin, px, grow)
        return (if (featherPx < 0.5f) grown else MaskOps.gaussianApprox(grown, featherPx)).resize(outW, outH)
    }

    /**
     * Paints [rgb] over [pixels] (ARGB, [w] × [h], modified in place) where [alpha] is set — except on-screen
     * text, which stays visible (same rule as the video shader).
     */
    /** Bilinear RGBA (0..1) of the garment map at cell position (x, y), or null where no garment is known. */
    private fun clothAt(c: ByteArray, cw: Int, ch: Int, x: Float, y: Float): FloatArray? {
        val x0 = x.toInt().coerceIn(0, cw - 1); val y0 = y.toInt().coerceIn(0, ch - 1)
        val x1 = min(cw - 1, x0 + 1); val y1 = min(ch - 1, y0 + 1)
        val fx = (x - x0).coerceIn(0f, 1f); val fy = (y - y0).coerceIn(0f, 1f)
        val out = FloatArray(4)
        for (k in 0..3) {
            fun v(xx: Int, yy: Int) = (c[(yy * cw + xx) * 4 + k].toInt() and 0xFF) / 255f
            val top = v(x0, y0) + (v(x1, y0) - v(x0, y0)) * fx
            val bot = v(x0, y1) + (v(x1, y1) - v(x0, y1)) * fx
            out[k] = top + (bot - top) * fy
        }
        return if (out[3] > 0.02f) out else null
    }

    fun paint(pixels: IntArray, w: Int, h: Int, alpha: ByteMask, rgb: Int, cloth: ByteArray? = null, clothW: Int = 0, clothH: Int = 0) {
        val text = TextGuard.mask(pixels, w, h)
        val cr = rgb shr 16 and 0xFF
        val cg = rgb shr 8 and 0xFF
        val cb = rgb and 0xFF
        for (i in 0 until w * h) {
            val a = alpha.data[i].toInt() and 0xFF
            if (a == 0 || text[i]) continue
            val f = a / 255f
            val p = pixels[i]
            val r = p shr 16 and 0xFF
            val g = p shr 8 and 0xFF
            val b = p and 0xFF
            var fr = cr
            var fg = cg
            var fb = cb
            if (cloth != null) clothAt(cloth, clothW, clothH, (i % w + 0.5f) * clothW / w - 0.5f, (i / w + 0.5f) * clothH / h - 0.5f)?.let { c ->
                // the garment continued over the skin, with the body's shading (same as the video shader)
                val lum = (0.299f * r + 0.587f * g + 0.114f * b) / 255f
                val shade = Math.pow((max(lum, 0.01f) / c[3]).toDouble(), 0.8).toFloat().coerceIn(0.6f, 1.35f)
                fr = (c[0] * shade * 255f).roundToInt().coerceIn(0, 255)
                fg = (c[1] * shade * 255f).roundToInt().coerceIn(0, 255)
                fb = (c[2] * shade * 255f).roundToInt().coerceIn(0, 255)
            }
            pixels[i] = (p and -0x1000000) or
                ((r + (fr - r) * f).roundToInt() shl 16) or ((g + (fg - g) * f).roundToInt() shl 8) or (b + (fb - b) * f).roundToInt()
        }
    }
}
