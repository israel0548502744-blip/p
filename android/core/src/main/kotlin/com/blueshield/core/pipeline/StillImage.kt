package com.blueshield.core.pipeline

import com.blueshield.core.CensorSettings
import com.blueshield.core.PipelineSpec
import com.blueshield.core.image.ByteMask
import com.blueshield.core.image.RgbImage
import com.blueshield.core.image.TextGuard
import com.blueshield.core.ml.ModelStore
import java.io.File
import kotlin.math.roundToInt

/**
 * Photos: the same analysis as a video, on a single frame. A photo can't accumulate face observations over
 * time, so one good look at a face is enough for a gender / age decision (the confidence threshold still
 * applies); everything else — people, ownership, neckline, text protection — is identical.
 */
object StillImage {
    fun analyze(models: ModelStore, settings: CensorSettings, spec: PipelineSpec, img: RgbImage, maskW: Int, maskH: Int, store: File): Analysis {
        val still = spec.copy(gender = spec.gender.copy(voteFactor = 1.0, minVotes = 1, minWeight = 0.3, minAgeVotes = 1))
        val analyzer = Analyzer(models, settings.copy(speed = "quality"), still, img.width, img.height, 1.0, maskW, maskH, store)
        analyzer.process(listOf(img))
        return analyzer.finish()
    }

    /** Feathered alpha (0..255) at [outW] × [outH] for the current decisions. */
    fun alpha(a: Analysis, settings: CensorSettings, decisions: Map<Int, Boolean>, outW: Int, outH: Int): ByteMask? {
        val raw = a.maskFor(0, decisions, settings, 0)
        if (!raw.any()) return null
        return Composer.feather(raw, outW, outH, settings.softness, settings.aggressive).resize(outW, outH)
    }

    /**
     * Paints [rgb] over [pixels] (ARGB, [w] × [h], modified in place) where [alpha] is set — except on-screen
     * text, which stays visible (same rule as the video shader).
     */
    fun paint(pixels: IntArray, w: Int, h: Int, alpha: ByteMask, rgb: Int) {
        val img = RgbImage(w, h, ByteArray(w * h * 3).also { b ->
            for (i in 0 until w * h) {
                val p = pixels[i]
                b[i * 3] = (p shr 16).toByte(); b[i * 3 + 1] = (p shr 8).toByte(); b[i * 3 + 2] = p.toByte()
            }
        })
        val text = TextGuard.mask(img)
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
            pixels[i] = (p and -0x1000000) or
                ((r + (cr - r) * f).roundToInt() shl 16) or ((g + (cg - g) * f).roundToInt() shl 8) or (b + (cb - b) * f).roundToInt()
        }
    }
}
