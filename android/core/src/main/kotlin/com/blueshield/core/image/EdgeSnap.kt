package com.blueshield.core.image

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fits a coarse censor mask (analysis resolution) to the real edges of the full-size picture: the mask is
 * upsampled and passed through a guided filter whose guide is the picture itself, so its boundary moves onto
 * the nearest strong edge (the outline of an arm or a hand) instead of following the coarse model's blobs.
 * The guide mixes brightness with a skin-tone channel (red minus green), so skin against a white dress or a
 * beige wall still has an edge to snap to.
 */
object EdgeSnap {
    /** Guide value per pixel, 0..~1.5 (ARGB pixels). */
    fun guide(pixels: IntArray): FloatArray = FloatArray(pixels.size) { i ->
        val p = pixels[i]
        val r = (p shr 16 and 0xFF) / 255f
        val g = (p shr 8 and 0xFF) / 255f
        val b = (p and 0xFF) / 255f
        0.299f * r + 0.587f * g + 0.114f * b + 1.5f * max(0f, r - g)
    }

    /**
     * Alpha 0..255 at [w] × [h] for [mask] (any size, 0/255) snapped to the edges of [pixels] (ARGB, w × h).
     * Works at most at [maxSide] (larger pictures are processed downscaled and the alpha scaled back up — or,
     * with [fullSize] false, returned at that working size).
     */
    fun snap(mask: ByteMask, pixels: IntArray, w: Int, h: Int, maxSide: Int = 1600, fullSize: Boolean = true): ByteMask {
        val s = min(1f, maxSide.toFloat() / max(w, h))
        val sw = max(1, (w * s).roundToInt())
        val sh = max(1, (h * s).roundToInt())
        val px = if (s < 1f) downscale(pixels, w, h, sw, sh) else pixels
        val g = guide(px)
        val p = FloatArray(sw * sh)
        val src = FloatArray(mask.data.size) { (mask.data[it].toInt() and 0xFF) / 255f }
        Resample.bilinear(src, mask.width, mask.height, mask.width.toFloat() / sw, mask.height.toFloat() / sh, 0f, 0f, p, sw, 0, 0, sw, sh, max = false)
        val diag = kotlin.math.hypot(sw.toFloat(), sh.toFloat())
        val band = max(3, (0.012f * diag).roundToInt())
        recolour(p, px, sw, sh, band)
        // the window must reach from the coarse boundary to the true edge: ~ one analysis pixel and a bit
        val r = max(2, (0.003f * diag).roundToInt())
        val q = Guided.filter(g, p, sw, sh, r, 0.0015f)
        val out = ByteMask(sw, sh, ByteArray(sw * sh) { i ->
            // a soft step around 0.5: crisp where the guide has an edge, graded where it has none
            val t = ((q[i] - 0.3f) / 0.4f).coerceIn(0f, 1f)
            (t * t * (3 - 2 * t) * 255f).roundToInt().toByte()
        })
        return if (s < 1f && fullSize) out.resize(w, h) else out
    }

    /**
     * Decides the pixels near the coarse boundary by colour: a colour histogram of the picture's sure skin
     * (deep inside the mask) against one of its sure surroundings (just outside it: the dress, the paper, the
     * wall next to the arm), then each pixel within [band] of the boundary takes its skin likelihood. This is
     * what moves the boundary from the model's blob onto the actual arm.
     */
    internal fun recolour(p: FloatArray, px: IntArray, w: Int, h: Int, band: Int) {
        // everything happens within 2 × band of the mask: work on that rectangle only (people rarely fill the frame)
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        for (y in 0 until h) {
            val o = y * w
            for (x in 0 until w) if (p[o + x] > 0.5f) {
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                y1 = y
            }
        }
        if (x1 < 0) return
        val m = 2 * band + 1
        x0 = max(0, x0 - m); y0 = max(0, y0 - m); x1 = min(w - 1, x1 + m); y1 = min(h - 1, y1 + m)
        if (x0 == 0 && y0 == 0 && x1 == w - 1 && y1 == h - 1) return recolourAll(p, px, w, h, band)
        val cw = x1 - x0 + 1
        val ch = y1 - y0 + 1
        val cp = FloatArray(cw * ch)
        val cpx = IntArray(cw * ch)
        for (y in 0 until ch) {
            System.arraycopy(p, (y0 + y) * w + x0, cp, y * cw, cw)
            System.arraycopy(px, (y0 + y) * w + x0, cpx, y * cw, cw)
        }
        recolourAll(cp, cpx, cw, ch, band)
        for (y in 0 until ch) System.arraycopy(cp, y * cw, p, (y0 + y) * w + x0, cw)
    }

    internal fun recolourAll(p: FloatArray, px: IntArray, w: Int, h: Int, band: Int) {
        val n = w * h
        val inside = ByteMask(w, h, ByteArray(n) { if (p[it] > 0.5f) -1 else 0 })
        if (!inside.any()) return
        // sure skin: deep inside the mask — but thin limbs (crowds, far people) have no "deep inside": then the
        // whole mask is the sample
        var core = MaskOps.erode(inside, band)
        if (core.data.count { it.toInt() != 0 } < 50) core = inside
        val outer = MaskOps.dilate(inside, band)
        val ring = MaskOps.dilate(inside, 2 * band)
        val bins = 16
        val skin = FloatArray(bins * bins * bins)
        val other = FloatArray(bins * bins * bins)
        fun bin(c: Int) = ((c shr 20 and 0xF) * bins + (c shr 12 and 0xF)) * bins + (c shr 4 and 0xF)
        var ns = 0
        var no = 0
        for (i in 0 until n) {
            if (core.data[i].toInt() != 0) { skin[bin(px[i])]++; ns++ }
            else if (ring.data[i].toInt() != 0 && outer.data[i].toInt() == 0) { other[bin(px[i])]++; no++ }
        }
        if (ns < 50 || no < 50) return
        // a little smoothing across neighbouring colours (one bin), then normalise
        val sk = blur3(skin, bins)
        val ot = blur3(other, bins)
        val ks = 1f / sk.sum()
        val ko = 1f / ot.sum()
        for (i in 0 until n) {
            if (outer.data[i].toInt() == 0 || core.data[i].toInt() != 0) continue
            val b = bin(px[i])
            val a = sk[b] * ks
            val o = ot[b] * ko
            val like = (a + 1e-6f) / (a + o + 2e-6f)
            // Only clear colour evidence moves the boundary: a pixel inside the mask is removed only when its
            // colour is clearly not this skin, one outside is added only when it clearly is. Ambiguous colours
            // (another person's skin next to this one in a crowd) keep the model's opinion.
            val inMask = inside.data[i].toInt() != 0
            if ((inMask && like < REMOVE_BELOW) || (!inMask && like > ADD_ABOVE)) p[i] = 0.75f * like + 0.25f * p[i]
        }
    }

    const val REMOVE_BELOW = 0.25f
    const val ADD_ABOVE = 0.8f

    private fun blur3(hist: FloatArray, bins: Int): FloatArray {
        var cur = hist
        for (axis in 0 until 3) {
            val out = FloatArray(cur.size)
            val stride = when (axis) { 0 -> bins * bins; 1 -> bins; else -> 1 }
            for (i in cur.indices) {
                val c = (i / stride) % bins
                var v = 2 * cur[i]
                if (c > 0) v += cur[i - stride]
                if (c < bins - 1) v += cur[i + stride]
                out[i] = v
            }
            cur = out
        }
        return cur
    }

    private fun downscale(pixels: IntArray, w: Int, h: Int, sw: Int, sh: Int): IntArray {
        val out = IntArray(sw * sh)
        for (y in 0 until sh) {
            val y0 = y * h / sh
            val y1 = max(y0 + 1, (y + 1) * h / sh)
            for (x in 0 until sw) {
                val x0 = x * w / sw
                val x1 = max(x0 + 1, (x + 1) * w / sw)
                var r = 0; var g = 0; var b = 0; var n = 0
                for (yy in y0 until y1) for (xx in x0 until x1) {
                    val p = pixels[yy * w + xx]
                    r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF; n++
                }
                out[y * sw + x] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
        return out
    }
}
