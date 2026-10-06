package com.blueshield.core.track

import com.blueshield.core.image.Box
import com.blueshield.core.image.FloatMask
import com.blueshield.core.image.RgbImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Anti-flicker fusion of per-frame soft skin masks (same algorithm as the desktop
 * `TemporalFuser`): motion-compensated state, fast attack, hysteresis, and an
 * evidence-anchored memory — on a freshly measured frame the past can only lift the
 * current score by at most [lift], never keep a pixel on by itself, so skin that moved
 * away is released at once and a moving arm leaves no trail.
 */
class TemporalFuser(private val memory: Float, private val lift: Float, onThreshold: Float, offRatio: Float) {
    private val on = onThreshold
    private val off = onThreshold * offRatio
    private var state: FloatMask? = null
    private var binary: BooleanArray? = null

    fun reset() {
        state = null
        binary = null
    }

    fun update(current: FloatMask, flow: OpticalFlow, fresh: Boolean): BooleanArray {
        val prevState = state
        val n = current.data.size
        val next = FloatMask(current.width, current.height)
        val prevBin: FloatMask? = binary?.let { b -> flow.warp(FloatMask(current.width, current.height, FloatArray(n) { if (b[it]) 1f else 0f })) }
        if (prevState == null) {
            System.arraycopy(current.data, 0, next.data, 0, n)
        } else {
            val warped = flow.warp(prevState)
            for (i in 0 until n) {
                val c = current.data[i]
                val w = warped.data[i]
                next.data[i] = if (fresh) {
                    if (c >= w) c else c * (1 - memory) + min(w, c + lift) * memory
                } else max(c, w * 0.97f)
            }
        }
        val out = BooleanArray(n) { i ->
            val s = next.data[i]
            s >= on || ((prevBin?.data?.get(i) ?: 0f) > 0.5f && s >= off)
        }
        state = next
        binary = out
        return out
    }
}

/** Scene-cut detector: HSV hue/saturation histogram correlation on a tiny thumbnail. */
class SceneCutDetector(private val threshold: Float) {
    private var prev: FloatArray? = null

    fun isCut(frame: RgbImage): Boolean {
        val h = Appearance.hsvHistogram(frame.resize(64, 36), 0, 0, 64, 36, 24, 16)
        val p = prev
        prev = h
        return p != null && Appearance.correlation(p, h) < 1f - threshold
    }
}

object Appearance {
    /** Normalised 2-D histogram over hue (0..180 like OpenCV) and saturation, in a pixel rectangle. */
    fun hsvHistogram(img: RgbImage, x0: Int, y0: Int, x1: Int, y1: Int, hBins: Int = 16, sBins: Int = 8): FloatArray {
        val hist = FloatArray(hBins * sBins)
        for (y in y0.coerceAtLeast(0) until y1.coerceAtMost(img.height)) for (x in x0.coerceAtLeast(0) until x1.coerceAtMost(img.width)) {
            val r = img.r(x, y) / 255f
            val g = img.g(x, y) / 255f
            val b = img.b(x, y) / 255f
            val mx = maxOf(r, g, b)
            val mn = minOf(r, g, b)
            val d = mx - mn
            var hue = when {
                d == 0f -> 0f
                mx == r -> 60f * (((g - b) / d) % 6f)
                mx == g -> 60f * ((b - r) / d + 2f)
                else -> 60f * ((r - g) / d + 4f)
            }
            if (hue < 0) hue += 360f
            val sat = if (mx == 0f) 0f else d / mx
            val hb = ((hue / 2f) / 180f * hBins).toInt().coerceIn(0, hBins - 1)
            val sb = (sat * sBins).toInt().coerceIn(0, sBins - 1)
            hist[hb * sBins + sb] += 1f
        }
        var norm = 0f
        for (v in hist) norm += v * v
        norm = sqrt(norm)
        if (norm > 0) for (i in hist.indices) hist[i] /= norm
        return hist
    }

    /** Pearson correlation of two histograms (OpenCV HISTCMP_CORREL). */
    fun correlation(a: FloatArray, b: FloatArray): Float {
        val ma = a.average().toFloat()
        val mb = b.average().toFloat()
        var num = 0f
        var da = 0f
        var db = 0f
        for (i in a.indices) {
            val x = a[i] - ma
            val y = b[i] - mb
            num += x * y
            da += x * x
            db += y * y
        }
        val den = sqrt(da * db)
        return if (den > 0) num / den else 1f
    }

    /** Torso-band appearance descriptor used for person re-identification. */
    fun torso(img: RgbImage, box: Box): FloatArray? {
        val bw = box.w
        val bh = box.h
        val x0 = (box.x1 + bw / 5).toInt()
        val x1 = (box.x2 - bw / 5).toInt()
        val y0 = (box.y1 + bh / 6).toInt()
        val y1 = (box.y1 + bh * 0.75f).toInt()
        if (x1 - x0 < 4 || y1 - y0 < 4) return null
        return hsvHistogram(img, x0, y0, x1, y1)
    }

    fun similarity(a: FloatArray?, b: FloatArray?): Float = if (a == null || b == null) 0.5f else max(0f, correlation(a, b))
}

/** One tracked sensitive region (NudeNet detection). */
class RegionTrack(var label: String, var box: Box, var score: Float) {
    var misses = 0
    /** Face tracks: frames in which a low neckline was seen below this face (sticky once established). */
    var cleavageFrames = 0
}

/** IoU tracker for sensitive regions with flow prediction and box smoothing. */
class RegionTracker(private val maxMisses: Int, private val smooth: Float = 0.55f) {
    val tracks = ArrayList<RegionTrack>()

    fun reset() = tracks.clear()

    fun predict(flow: OpticalFlow, aw: Int, ah: Int) {
        for (t in tracks) {
            val (dx, dy) = flow.boxShift(t.box, aw, ah)
            t.box = t.box.shift(dx, dy)
        }
    }

    fun update(dets: List<Detection>) {
        val unmatched = dets.indices.toMutableList()
        for (t in tracks) {
            var best = -1
            var bestIou = 0.2f
            for (i in unmatched) {
                val iou = t.box.iou(dets[i].box)
                if (iou > bestIou) {
                    best = i
                    bestIou = iou
                }
            }
            if (best < 0) {
                t.misses++
                continue
            }
            unmatched.remove(best)
            val d = dets[best]
            t.box = t.box.lerp(d.box, smooth)
            t.score = max(d.score, 0.7f * t.score)
            t.label = d.label
            t.misses = 0
        }
        for (i in unmatched) tracks += RegionTrack(dets[i].label, dets[i].box, dets[i].score)
        tracks.removeAll { it.misses > maxMisses }
    }
}

/** A labelled detection in analysis-frame pixels. */
data class Detection(val label: String, val score: Float, val box: Box)
