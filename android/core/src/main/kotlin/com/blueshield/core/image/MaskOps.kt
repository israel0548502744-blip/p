package com.blueshield.core.image

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object MaskOps {
    fun areaDownscale(src: FloatMask, w: Int, h: Int): FloatMask {
        val out = FloatMask(w, h)
        val sx = src.width.toFloat() / w
        val sy = src.height.toFloat() / h
        for (y in 0 until h) {
            val ya = (y * sy).toInt()
            val yb = max(ya + 1, ((y + 1) * sy).toInt().coerceAtMost(src.height))
            for (x in 0 until w) {
                val xa = (x * sx).toInt()
                val xb = max(xa + 1, ((x + 1) * sx).toInt().coerceAtMost(src.width))
                var s = 0f
                for (yy in ya until yb) for (xx in xa until xb) s += src.data[yy * src.width + xx]
                out.data[y * w + x] = s / ((yb - ya) * (xb - xa))
            }
        }
        return out
    }

    /** Morphological dilation with a square of the given radius (separable running max). */
    fun dilate(m: ByteMask, radius: Int): ByteMask {
        if (radius <= 0) return m.copy()
        val w = m.width
        val h = m.height
        val tmp = ByteArray(w * h)
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = 0
            for (k in max(0, x - radius)..min(w - 1, x + radius)) v = max(v, m.data[y * w + k].toInt() and 0xFF)
            tmp[y * w + x] = v.toByte()
        }
        for (x in 0 until w) for (y in 0 until h) {
            var v = 0
            for (k in max(0, y - radius)..min(h - 1, y + radius)) v = max(v, tmp[k * w + x].toInt() and 0xFF)
            out[y * w + x] = v.toByte()
        }
        return ByteMask(w, h, out)
    }

    /** Three box-blur passes ≈ Gaussian blur with the given sigma (O(1) per pixel per pass). */
    fun gaussianApprox(m: ByteMask, sigma: Float): ByteMask {
        if (sigma < 0.5f) return m.copy()
        val r = max(1, ((sqrt(12f * sigma * sigma / 3f + 1f) - 1f) / 2f).roundToInt())
        var cur = FloatArray(m.data.size) { (m.data[it].toInt() and 0xFF).toFloat() }
        repeat(3) {
            cur = boxPass(cur, m.width, m.height, r, horizontal = true)
            cur = boxPass(cur, m.width, m.height, r, horizontal = false)
        }
        return ByteMask(m.width, m.height, ByteArray(cur.size) { (cur[it] + 0.5f).toInt().coerceIn(0, 255).toByte() })
    }

    private fun boxPass(src: FloatArray, w: Int, h: Int, r: Int, horizontal: Boolean): FloatArray {
        val out = FloatArray(src.size)
        val n = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val norm = 1f / (2 * r + 1)
        for (line in 0 until lines) {
            fun idx(i: Int) = if (horizontal) line * w + i else i * w + line
            var acc = 0f
            for (k in -r..r) acc += src[idx(k.coerceIn(0, n - 1))]
            for (i in 0 until n) {
                out[idx(i)] = acc * norm
                acc += src[idx((i + r + 1).coerceAtMost(n - 1))] - src[idx((i - r).coerceAtLeast(0))]
            }
        }
        return out
    }

    /**
     * 8-connected component labelling of the non-zero pixels (two-pass union-find).
     * Returns labels (0 = background, 1..n) and the component count + 1.
     */
    fun connectedComponents(on: BooleanArray, w: Int, h: Int): Pair<IntArray, Int> {
        val labels = IntArray(w * h)
        val parent = IntArray(w * h / 2 + 2) { it }
        var next = 1
        var parentArr = parent
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (!on[i]) continue
            var best = 0
            val nb = intArrayOf(
                if (x > 0) labels[i - 1] else 0,
                if (y > 0 && x > 0) labels[i - w - 1] else 0,
                if (y > 0) labels[i - w] else 0,
                if (y > 0 && x < w - 1) labels[i - w + 1] else 0,
            )
            for (l in nb) if (l != 0 && (best == 0 || l < best)) best = l
            if (best == 0) {
                if (next >= parentArr.size) parentArr = parentArr.copyOf(parentArr.size * 2).also { arr ->
                    for (k in parentArr.size until arr.size) arr[k] = k
                }
                parentArr[next] = next
                labels[i] = next++
            } else {
                labels[i] = best
                for (l in nb) if (l != 0 && l != best) unionIn(parentArr, best, l)
            }
        }
        // flatten + compact
        val remap = IntArray(next)
        var count = 1
        for (l in 1 until next) {
            val r = findIn(parentArr, l)
            if (remap[r] == 0) remap[r] = count++
            remap[l] = remap[r]
        }
        for (i in labels.indices) if (labels[i] != 0) labels[i] = remap[labels[i]]
        return labels to count
    }

    private fun findIn(p: IntArray, a: Int): Int {
        var x = a
        while (p[x] != x) {
            p[x] = p[p[x]]
            x = p[x]
        }
        return x
    }

    private fun unionIn(p: IntArray, a: Int, b: Int) {
        val ra = findIn(p, a)
        val rb = findIn(p, b)
        if (ra != rb) p[max(ra, rb)] = min(ra, rb)
    }

    /** Filled axis-aligned ellipse, keeping the max with existing values. */
    fun fillEllipse(m: ByteMask, cx: Float, cy: Float, ax: Float, ay: Float, value: Int) {
        if (ax < 1f || ay < 1f) return
        val y0 = max(0, (cy - ay).toInt())
        val y1 = min(m.height - 1, (cy + ay).toInt() + 1)
        for (y in y0..y1) {
            val dy = (y + 0.5f - cy) / ay
            val span = 1f - dy * dy
            if (span < 0f) continue
            val dx = ax * sqrt(span)
            val x0 = max(0, (cx - dx).roundToInt())
            val x1 = min(m.width - 1, (cx + dx).roundToInt())
            for (x in x0..x1) if ((m.data[y * m.width + x].toInt() and 0xFF) < value) m.data[y * m.width + x] = value.toByte()
        }
    }
}
