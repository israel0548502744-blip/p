package com.blueshield.core.track

import com.blueshield.core.image.Box
import com.blueshield.core.image.FloatMask
import com.blueshield.core.image.Par
import com.blueshield.core.image.RgbImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Dense optical flow (pyramidal Lucas–Kanade on a ~128 px grayscale copy).
 *
 * Produces a *backward* flow field: for each pixel of the current frame, where it
 * was in the previous frame. Masks from the previous frame can then be warped
 * forward so censorship sticks to moving people between detector keyframes.
 * (The desktop app uses OpenCV's Farnebäck flow for the same purpose.)
 */
class OpticalFlow(val width: Int, val height: Int, flowSide: Int = 128) {
    private val scale = min(1f, flowSide.toFloat() / max(width, height))
    val fw = max(16, (width * scale).toInt())
    val fh = max(16, (height * scale).toInt())
    private var prev: List<FloatMask>? = null
    /** Backward flow at flow resolution, in flow pixels (null until two frames were seen). */
    var u: FloatArray? = null
        private set
    var v: FloatArray? = null
        private set

    fun reset() {
        prev = null
        u = null
        v = null
        maxFlowCache = -1f
    }

    fun update(frame: RgbImage) {
        val g = frame.resize(fw, fh).gray()
        val pyr = pyramid(g, 3)
        val p = prev
        prev = pyr
        if (p == null) {
            u = null
            v = null
            maxFlowCache = -1f
            return
        }
        // estimate flow from current -> previous, coarse to fine
        var fu = FloatArray(pyr.last().width * pyr.last().height)
        var fv = FloatArray(fu.size)
        for (level in pyr.indices.reversed()) {
            val cur = pyr[level]
            val old = p[level]
            if (level != pyr.lastIndex) {
                val up = upsample(fu, fv, pyr[level + 1].width, pyr[level + 1].height, cur.width, cur.height)
                fu = up.first
                fv = up.second
            }
            lucasKanade(cur, old, fu, fv, window = 3, iterations = 2)
        }
        u = fu
        v = fv
        maxFlowCache = -1f
    }

    /** Mean absolute motion in analysis pixels (for adaptive keyframes). */
    fun meanMotion(): Float {
        val uu = u ?: return 0f
        val vv = v ?: return 0f
        var s = 0f
        for (i in uu.indices step 4) s += abs(uu[i]) + abs(vv[i])
        return s / (uu.size / 4f) / scale / 2f
    }

    /** Largest |flow| component in flow pixels (cached per update). */
    private var maxFlowCache = -1f

    private fun maxFlow(): Float {
        if (maxFlowCache >= 0f) return maxFlowCache
        var m = 0f
        u?.let { for (x in it) m = max(m, abs(x)) }
        v?.let { for (x in it) m = max(m, abs(x)) }
        maxFlowCache = m
        return m
    }

    /**
     * Warp a mask (at analysis resolution) from the previous frame into the current one.
     * Only the neighbourhood of the mask's non-zero pixels (grown by the largest motion) can be
     * non-zero afterwards, so only that rectangle is resampled — masks are mostly empty.
     */
    fun warp(mask: FloatMask): FloatMask {
        val uu = u ?: return mask
        val vv = v ?: return mask
        val mw = mask.width
        val mh = mask.height
        val out = FloatMask(mw, mh)
        var bx0 = mw
        var by0 = mh
        var bx1 = -1
        var by1 = -1
        val d = mask.data
        for (y in 0 until mh) {
            val r = y * mw
            var first = -1
            var last = -1
            for (x in 0 until mw) if (d[r + x] != 0f) {
                if (first < 0) first = x
                last = x
            }
            if (first >= 0) {
                if (first < bx0) bx0 = first
                if (last > bx1) bx1 = last
                if (y < by0) by0 = y
                by1 = y
            }
        }
        if (bx1 < 0) return out
        val sx = fw.toFloat() / mw
        val sy = fh.toFloat() / mh
        val grow = (maxFlow() / min(sx, sy)).toInt() + 2
        val x0 = max(0, bx0 - grow)
        val x1 = min(mw - 1, bx1 + grow)
        val y0 = max(0, by0 - grow)
        val y1 = min(mh - 1, by1 + grow)
        val col = IntArray(x1 - x0 + 1) { ((x0 + it + 0.5f) * sx - 0.5f).coerceIn(0f, fw - 1f).toInt() }
        Par.range(y0, y1 + 1, x1 - x0 + 1) { y ->
            val row = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, fh - 1f).toInt() * fw
            val o = y * mw
            for (x in x0..x1) {
                val i = row + col[x - x0]
                out.data[o + x] = mask.sample(x + uu[i] / sx, y + vv[i] / sy)
            }
        }
        return out
    }

    /** Median forward motion (analysis pixels) inside a box given in analysis pixels. */
    fun boxShift(box: Box, analysisW: Int, analysisH: Int): Pair<Float, Float> {
        val uu = u ?: return 0f to 0f
        val vv = v ?: return 0f to 0f
        val sx = fw.toFloat() / analysisW
        val sy = fh.toFloat() / analysisH
        val x0 = (box.x1 * sx).toInt().coerceIn(0, fw - 1)
        val x1 = (box.x2 * sx).toInt().coerceIn(x0 + 1, fw)
        val y0 = (box.y1 * sy).toInt().coerceIn(0, fh - 1)
        val y1 = (box.y2 * sy).toInt().coerceIn(y0 + 1, fh)
        val us = ArrayList<Float>()
        val vs = ArrayList<Float>()
        for (y in y0 until y1) for (x in x0 until x1) {
            us += uu[y * fw + x]
            vs += vv[y * fw + x]
        }
        if (us.isEmpty()) return 0f to 0f
        us.sort()
        vs.sort()
        return -us[us.size / 2] / sx to -vs[vs.size / 2] / sy
    }

    private fun pyramid(g: FloatMask, levels: Int): List<FloatMask> {
        val out = arrayListOf(g)
        while (out.size < levels && out.last().width >= 32 && out.last().height >= 32) {
            val p = out.last()
            out += p.resize(p.width / 2, p.height / 2)
        }
        return out
    }

    private fun upsample(u: FloatArray, v: FloatArray, w: Int, h: Int, nw: Int, nh: Int): Pair<FloatArray, FloatArray> {
        val su = FloatMask(w, h, u).resize(nw, nh)
        val sv = FloatMask(w, h, v).resize(nw, nh)
        val kx = nw.toFloat() / w
        val ky = nh.toFloat() / h
        for (i in su.data.indices) {
            su.data[i] *= kx
            sv.data[i] *= ky
        }
        return su.data to sv.data
    }

    /**
     * Per-pixel iterative LK refining (u, v) so that cur(x) ≈ old(x + (u, v)).
     *
     * Only *well-conditioned* windows (both structure-tensor eigenvalues large: real texture, not sky,
     * a wall or a pool-table felt) are trusted. Elsewhere plain LK returns huge, random vectors that would
     * drag masks across the frame, so those pixels keep the coarser estimate and then take the
     * confidence-weighted average of their neighbourhood.
     */
    private fun lucasKanade(cur: FloatMask, old: FloatMask, u: FloatArray, v: FloatArray, window: Int, iterations: Int) {
        val w = cur.width
        val h = cur.height
        val ix = FloatArray(w * h)
        val iy = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val xm = max(0, x - 1)
            val xp = min(w - 1, x + 1)
            val ym = max(0, y - 1)
            val yp = min(h - 1, y + 1)
            ix[y * w + x] = (old[xp, y] - old[xm, y]) * 0.5f
            iy[y * w + x] = (old[x, yp] - old[x, ym]) * 0.5f
        }
        val n = (2 * window + 1) * (2 * window + 1)
        val minEig = MIN_EIGEN_PER_PIXEL * n
        val conf = FloatArray(w * h)
        val prior = u.copyOf() to v.copyOf()
        repeat(iterations) {
            val nu = u.copyOf()
            val nv = v.copyOf()
            Par.rows(h, w * n) { y -> for (x in 0 until w) {
                var a11 = 0f
                var a12 = 0f
                var a22 = 0f
                var b1 = 0f
                var b2 = 0f
                val i0 = y * w + x
                for (dy in -window..window) {
                    val yy = (y + dy).coerceIn(0, h - 1)
                    for (dx in -window..window) {
                        val xx = (x + dx).coerceIn(0, w - 1)
                        val i = yy * w + xx
                        val gx = ix[i]
                        val gy = iy[i]
                        val it = old.sample(xx + u[i0], yy + v[i0]) - cur.data[i]
                        a11 += gx * gx
                        a12 += gx * gy
                        a22 += gy * gy
                        b1 += gx * it
                        b2 += gy * it
                    }
                }
                val half = (a11 + a22) / 2
                val lambdaMin = half - sqrt(((a11 - a22) / 2) * ((a11 - a22) / 2) + a12 * a12)
                if (lambdaMin > minEig) {
                    val det = a11 * a22 - a12 * a12
                    // one LK step is at most a couple of pixels at this level; bigger jumps are mismatches
                    nu[i0] = u[i0] - ((a22 * b1 - a12 * b2) / det).coerceIn(-2f, 2f)
                    nv[i0] = v[i0] - ((a11 * b2 - a12 * b1) / det).coerceIn(-2f, 2f)
                    conf[i0] = lambdaMin / (lambdaMin + 4 * minEig)
                } else {
                    nu[i0] = prior.first[i0]
                    nv[i0] = prior.second[i0]
                    conf[i0] = 0f
                }
            } }
            System.arraycopy(nu, 0, u, 0, u.size)
            System.arraycopy(nv, 0, v, 0, v.size)
        }
        // confidence-weighted smoothing: textureless pixels inherit their textured neighbours' motion
        // (or the coarser level's estimate when there are none)
        val r = max(2, max(w, h) / 24)
        val cu = boxBlur(FloatArray(w * h) { u[it] * conf[it] }, w, h, r)
        val cv = boxBlur(FloatArray(w * h) { v[it] * conf[it] }, w, h, r)
        val cc = boxBlur(conf, w, h, r)
        for (i in u.indices) {
            val k = cc[i]
            val smoothU = if (k > 1e-4f) cu[i] / k else prior.first[i]
            val smoothV = if (k > 1e-4f) cv[i] / k else prior.second[i]
            val keep = conf[i]
            val wPrior = (0.05f - k).coerceIn(0f, 0.05f) / 0.05f // no textured support at all → coarse estimate
            val su = smoothU * (1 - wPrior) + prior.first[i] * wPrior
            val sv = smoothV * (1 - wPrior) + prior.second[i] * wPrior
            u[i] = u[i] * keep + su * (1 - keep)
            v[i] = v[i] * keep + sv * (1 - keep)
        }
        // keep the field sane
        val lim = max(w, h) / 6f
        for (i in u.indices) {
            u[i] = u[i].coerceIn(-lim, lim)
            v[i] = v[i].coerceIn(-lim, lim)
        }
    }

    private fun boxBlur(src: FloatArray, w: Int, h: Int, r: Int): FloatArray {
        val tmp = FloatArray(w * h)
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            var acc = 0f
            for (k in -r..r) acc += src[y * w + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                tmp[y * w + x] = acc
                acc += src[y * w + (x + r + 1).coerceAtMost(w - 1)] - src[y * w + (x - r).coerceAtLeast(0)]
            }
        }
        for (x in 0 until w) {
            var acc = 0f
            for (k in -r..r) acc += tmp[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                out[y * w + x] = acc
                acc += tmp[(y + r + 1).coerceAtMost(h - 1) * w + x] - tmp[(y - r).coerceAtLeast(0) * w + x]
            }
        }
        return out
    }

    private companion object {
        /** Minimum structure-tensor eigenvalue per window pixel (gray levels²) for a trustworthy LK estimate. */
        const val MIN_EIGEN_PER_PIXEL = 6f
    }
}
