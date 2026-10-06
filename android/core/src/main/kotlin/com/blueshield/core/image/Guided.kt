package com.blueshield.core.image

/**
 * Edge-aware mask refinement (He et al., "Guided Image Filtering"): the coarse skin probability from the
 * 256 px segmentation model is re-fitted locally to the frame's own luminance, so mask edges snap to the
 * real outline of arms and shoulders instead of a blocky, upsampled one. O(N) via box filters.
 */
object Guided {
    /** [guide]: luminance 0..1; [p]: probability 0..1; [r]: window radius in pixels; [eps]: edge sensitivity. */
    fun filter(guide: FloatArray, p: FloatArray, w: Int, h: Int, r: Int, eps: Float): FloatArray {
        val n = w * h
        val ip = FloatArray(n) { guide[it] * p[it] }
        val ii = FloatArray(n) { guide[it] * guide[it] }
        val meanI = box(guide, w, h, r)
        val meanP = box(p, w, h, r)
        val corrI = box(ii, w, h, r)
        val corrIP = box(ip, w, h, r)
        val a = FloatArray(n)
        val b = FloatArray(n)
        for (i in 0 until n) {
            val varI = corrI[i] - meanI[i] * meanI[i]
            val cov = corrIP[i] - meanI[i] * meanP[i]
            a[i] = cov / (varI + eps)
            b[i] = meanP[i] - a[i] * meanI[i]
        }
        val meanA = box(a, w, h, r)
        val meanB = box(b, w, h, r)
        return FloatArray(n) { (meanA[it] * guide[it] + meanB[it]).coerceIn(0f, 1f) }
    }

    /** Normalised box mean with clamped borders (separable running sums). */
    fun box(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        val norm = 1f / (2 * r + 1)
        Par.rows(h, w) { y ->
            val o = y * w
            var acc = 0f
            for (k in -r..r) acc += src[o + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[o + x] = acc * norm
                acc += src[o + (x + r + 1).coerceAtMost(w - 1)] - src[o + (x - r).coerceAtLeast(0)]
            }
        }
        Par.rows(w, h) { x ->
            var acc = 0f
            for (k in -r..r) acc += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = acc * norm
                acc += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }
}
