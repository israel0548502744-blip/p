package com.blueshield.core.track

import com.blueshield.core.image.Box
import com.blueshield.core.image.FloatMask
import com.blueshield.core.image.RgbImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
    }

    fun update(frame: RgbImage) {
        val g = frame.resize(fw, fh).gray()
        val pyr = pyramid(g, 3)
        val p = prev
        prev = pyr
        if (p == null) {
            u = null
            v = null
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
    }

    /** Mean absolute motion in analysis pixels (for adaptive keyframes). */
    fun meanMotion(): Float {
        val uu = u ?: return 0f
        val vv = v ?: return 0f
        var s = 0f
        for (i in uu.indices step 4) s += abs(uu[i]) + abs(vv[i])
        return s / (uu.size / 4f) / scale / 2f
    }

    /** Warp a mask (at analysis resolution) from the previous frame into the current one. */
    fun warp(mask: FloatMask): FloatMask {
        val uu = u ?: return mask
        val vv = v ?: return mask
        val out = FloatMask(mask.width, mask.height)
        val sx = fw.toFloat() / mask.width
        val sy = fh.toFloat() / mask.height
        for (y in 0 until mask.height) for (x in 0 until mask.width) {
            val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, fw - 1f)
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, fh - 1f)
            val i = fy.toInt() * fw + fx.toInt()
            out.data[y * mask.width + x] = mask.sample(x + uu[i] / sx, y + vv[i] / sy)
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

    /** Per-pixel iterative LK refining (u, v) so that cur(x) ≈ old(x + (u, v)). */
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
        repeat(iterations) {
            val nu = u.copyOf()
            val nv = v.copyOf()
            for (y in 0 until h) for (x in 0 until w) {
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
                val det = a11 * a22 - a12 * a12
                if (det > 1e-3f) {
                    nu[i0] = u[i0] - (a22 * b1 - a12 * b2) / det
                    nv[i0] = v[i0] - (a11 * b2 - a12 * b1) / det
                }
            }
            System.arraycopy(nu, 0, u, 0, u.size)
            System.arraycopy(nv, 0, v, 0, v.size)
        }
        // keep the field sane
        val lim = max(w, h) / 3f
        for (i in u.indices) {
            u[i] = u[i].coerceIn(-lim, lim)
            v[i] = v[i].coerceIn(-lim, lim)
        }
    }
}
