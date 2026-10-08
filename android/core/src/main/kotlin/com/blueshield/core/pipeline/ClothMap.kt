package com.blueshield.core.pipeline

import com.blueshield.core.image.ByteMask
import com.blueshield.core.image.FloatMask
import com.blueshield.core.image.MaskOps
import com.blueshield.core.image.RgbImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Continue the clothes" instead of a solid colour: for every bit of skin, the colour of the garment next to it
 * (the segmenter's clothes class), spread over the skin from its edge inwards (push–pull), plus the local mean
 * brightness so the renderer can keep the body's shading — an arm becomes a sleeve of the shirt, a neckline
 * becomes a high collar. Computed per analysed frame on a coarse grid ([SCALE] analysis pixels per cell);
 * the result is RGBA: fill colour + mean luminance (A ≥ 1), and A = 0 where no garment is near.
 */
object ClothMap {
    const val SCALE = 4
    /** Clothes within this many cells of skin count as its garment. */
    private const val NEAR = 8
    /** Push–pull levels: how far (2^LEVELS cells) a garment colour may travel into a bare area. */
    private const val LEVELS = 5
    private const val CLOTHES_MIN = 0.6f

    fun size(w: Int, h: Int) = (w + SCALE - 1) / SCALE to (h + SCALE - 1) / SCALE

    fun compute(frame: RgbImage, skin: BooleanArray, clothes: FloatMask): ByteArray {
        val w = frame.width
        val h = frame.height
        val (cw, ch) = size(w, h)
        val n = cw * ch
        val out = ByteArray(n * 4)
        // per cell: mean colour, skin share, clothes probability
        val col = FloatArray(n * 3)
        val skinShare = FloatArray(n)
        val cloth = FloatArray(n)
        val cnt = FloatArray(n)
        for (y in 0 until h) {
            val cy = y / SCALE
            for (x in 0 until w) {
                val c = cy * cw + x / SCALE
                val i = y * w + x
                col[c * 3] += frame.data[i * 3].toInt() and 0xFF
                col[c * 3 + 1] += frame.data[i * 3 + 1].toInt() and 0xFF
                col[c * 3 + 2] += frame.data[i * 3 + 2].toInt() and 0xFF
                if (skin[i]) skinShare[c] += 1f
                cloth[c] += clothes.data[i]
                cnt[c] += 1f
            }
        }
        var anySkin = false
        for (c in 0 until n) {
            val k = 1f / max(1f, cnt[c])
            col[c * 3] *= k; col[c * 3 + 1] *= k; col[c * 3 + 2] *= k
            skinShare[c] *= k
            cloth[c] *= k
            if (skinShare[c] > 0f) anySkin = true
        }
        if (!anySkin) return out
        val skinCells = ByteMask(cw, ch, ByteArray(n) { if (skinShare[it] > 0f) -1 else 0 })
        val near = MaskOps.dilate(skinCells, NEAR)
        val region = MaskOps.dilate(skinCells, 3)
        // garment cells next to the skin are the sources
        val wt = FloatArray(n) { if (cloth[it] >= CLOTHES_MIN && skinShare[it] < 0.05f && near.data[it].toInt() != 0) 1f else 0f }
        val fill = pushPull(col, wt, cw, ch)
        val lum = FloatArray(n) { 0.299f * col[it * 3] + 0.587f * col[it * 3 + 1] + 0.114f * col[it * 3 + 2] }
        val lm = blur(lum, cw, ch, 3)
        for (c in 0 until n) {
            if (region.data[c].toInt() == 0) continue
            val fw = fill[c * 4 + 3]
            if (fw < 1e-3f) continue
            out[c * 4] = (fill[c * 4] / fw).roundToInt().coerceIn(0, 255).toByte()
            out[c * 4 + 1] = (fill[c * 4 + 1] / fw).roundToInt().coerceIn(0, 255).toByte()
            out[c * 4 + 2] = (fill[c * 4 + 2] / fw).roundToInt().coerceIn(0, 255).toByte()
            out[c * 4 + 3] = lm[c].roundToInt().coerceIn(1, 255).toByte()
        }
        return out
    }

    /** Pull–push hole filling: colours (weighted by [wt]) carried over the cells without, coarse levels filling gaps. */
    private fun pushPull(col: FloatArray, wt: FloatArray, w: Int, h: Int): FloatArray {
        // level 0: premultiplied colour + weight, 4 floats per cell
        val levels = ArrayList<Triple<FloatArray, Int, Int>>()
        var cur = FloatArray(w * h * 4) { i -> val c = i / 4; val k = i % 4; if (k == 3) wt[c] else col[c * 3 + k] * wt[c] }
        var cw = w
        var ch = h
        levels += Triple(cur, cw, ch)
        repeat(LEVELS) {
            if (cw < 2 && ch < 2) return@repeat
            val nw = max(1, (cw + 1) / 2)
            val nh = max(1, (ch + 1) / 2)
            val next = FloatArray(nw * nh * 4)
            for (y in 0 until nh) for (x in 0 until nw) {
                var m = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val sx = min(cw - 1, 2 * x + dx)
                    val sy = min(ch - 1, 2 * y + dy)
                    for (k in 0..3) next[(y * nw + x) * 4 + k] += cur[(sy * cw + sx) * 4 + k]
                    m++
                }
                for (k in 0..3) next[(y * nw + x) * 4 + k] /= m
            }
            cur = next; cw = nw; ch = nh
            levels += Triple(cur, cw, ch)
        }
        var (c, lw, lh) = levels.last()
        for (li in levels.size - 2 downTo 0) {
            val (fine, fw, fh) = levels[li]
            val res = FloatArray(fw * fh * 4)
            for (y in 0 until fh) for (x in 0 until fw) {
                val o = (y * fw + x) * 4
                val u = (min(lh - 1, y / 2) * lw + min(lw - 1, x / 2)) * 4
                val a = min(1f, fine[o + 3] * 4f) // where the finer level has data, trust it
                for (k in 0..3) res[o + k] = fine[o + k] + (1f - a) * c[u + k]
            }
            c = res; lw = fw; lh = fh
        }
        return c
    }

    private fun blur(v: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        var cur = v
        repeat(2) {
            val tmp = FloatArray(cur.size)
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f; var k = 0
                for (d in -r..r) { val xx = x + d; if (xx in 0 until w) { s += cur[y * w + xx]; k++ } }
                tmp[y * w + x] = s / k
            }
            val out = FloatArray(cur.size)
            for (y in 0 until h) for (x in 0 until w) {
                var s = 0f; var k = 0
                for (d in -r..r) { val yy = y + d; if (yy in 0 until h) { s += tmp[yy * w + x]; k++ } }
                out[y * w + x] = s / k
            }
            cur = out
        }
        return cur
    }
}
