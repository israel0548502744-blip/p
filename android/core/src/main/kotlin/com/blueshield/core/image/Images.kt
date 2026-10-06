package com.blueshield.core.image

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Interleaved 8-bit RGB image. Platform-neutral so the pipeline runs on Android and the JVM alike. */
class RgbImage(val width: Int, val height: Int, val data: ByteArray = ByteArray(width * height * 3)) {
    init {
        require(data.size == width * height * 3) { "RGB buffer has ${data.size} bytes, expected ${width * height * 3}" }
    }

    fun r(x: Int, y: Int) = data[(y * width + x) * 3].toInt() and 0xFF
    fun g(x: Int, y: Int) = data[(y * width + x) * 3 + 1].toInt() and 0xFF
    fun b(x: Int, y: Int) = data[(y * width + x) * 3 + 2].toInt() and 0xFF

    /** Bilinear resize (area-averaged when shrinking by more than 2x, via a box pre-filter). */
    fun resize(w: Int, h: Int): RgbImage {
        if (w == width && h == height) return this
        val src = if (width >= 2 * w && height >= 2 * h) halve() else this
        if (src !== this) return src.resize(w, h)
        val out = RgbImage(w, h)
        val sx = width.toFloat() / w
        val sy = height.toFloat() / h
        val d = out.data
        Par.rows(h, w) { y ->
            val fy = max(0f, (y + 0.5f) * sy - 0.5f)
            val y0 = min(floor(fy).toInt(), height - 1)
            val y1 = min(y0 + 1, height - 1)
            val wy = fy - y0
            for (x in 0 until w) {
                val fx = max(0f, (x + 0.5f) * sx - 0.5f)
                val x0 = min(floor(fx).toInt(), width - 1)
                val x1 = min(x0 + 1, width - 1)
                val wx = fx - x0
                val o = (y * w + x) * 3
                for (c in 0 until 3) {
                    val a = data[(y0 * width + x0) * 3 + c].toInt() and 0xFF
                    val b = data[(y0 * width + x1) * 3 + c].toInt() and 0xFF
                    val cc = data[(y1 * width + x0) * 3 + c].toInt() and 0xFF
                    val dd = data[(y1 * width + x1) * 3 + c].toInt() and 0xFF
                    val top = a + (b - a) * wx
                    val bot = cc + (dd - cc) * wx
                    d[o + c] = (top + (bot - top) * wy + 0.5f).toInt().coerceIn(0, 255).toByte()
                }
            }
        }
        return out
    }

    private fun halve(): RgbImage {
        val w = width / 2
        val h = height / 2
        val out = RgbImage(w, h)
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until 3) {
            val s = (data[((2 * y) * width + 2 * x) * 3 + c].toInt() and 0xFF) +
                (data[((2 * y) * width + 2 * x + 1) * 3 + c].toInt() and 0xFF) +
                (data[((2 * y + 1) * width + 2 * x) * 3 + c].toInt() and 0xFF) +
                (data[((2 * y + 1) * width + 2 * x + 1) * 3 + c].toInt() and 0xFF)
            out.data[(y * w + x) * 3 + c] = ((s + 2) / 4).toByte()
        }
        return out
    }

    /** Crop with edge replication for out-of-bounds pixels. */
    fun crop(x0: Int, y0: Int, w: Int, h: Int): RgbImage {
        val out = RgbImage(w, h)
        for (y in 0 until h) {
            val sy = (y0 + y).coerceIn(0, height - 1)
            for (x in 0 until w) {
                val sx = (x0 + x).coerceIn(0, width - 1)
                System.arraycopy(data, (sy * width + sx) * 3, out.data, (y * w + x) * 3, 3)
            }
        }
        return out
    }

    /** Pad right/bottom with black to a square (NudeNet letterboxing). */
    fun padToSquare(): RgbImage {
        val s = max(width, height)
        if (s == width && s == height) return this
        val out = RgbImage(s, s)
        for (y in 0 until height) System.arraycopy(data, y * width * 3, out.data, y * s * 3, width * 3)
        return out
    }

    /** Affine warp (dst -> src mapping given as the inverse matrix), bilinear, edge-replicated. */
    fun warpAffineInverse(inv: FloatArray, w: Int, h: Int): RgbImage {
        val out = RgbImage(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = inv[0] * x + inv[1] * y + inv[2]
            val sy = inv[3] * x + inv[4] * y + inv[5]
            val fx = sx.coerceIn(0f, width - 1f)
            val fy = sy.coerceIn(0f, height - 1f)
            val x0 = floor(fx).toInt()
            val y0 = floor(fy).toInt()
            val x1 = min(x0 + 1, width - 1)
            val y1 = min(y0 + 1, height - 1)
            val wx = fx - x0
            val wy = fy - y0
            for (c in 0 until 3) {
                val a = data[(y0 * width + x0) * 3 + c].toInt() and 0xFF
                val b = data[(y0 * width + x1) * 3 + c].toInt() and 0xFF
                val cc = data[(y1 * width + x0) * 3 + c].toInt() and 0xFF
                val dd = data[(y1 * width + x1) * 3 + c].toInt() and 0xFF
                val top = a + (b - a) * wx
                val bot = cc + (dd - cc) * wx
                out.data[(y * w + x) * 3 + c] = (top + (bot - top) * wy + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
        }
        return out
    }

    /** Luma plane (BT.601), used by optical flow and scene-cut detection. */
    fun gray(): FloatMask {
        val out = FloatMask(width, height)
        for (i in 0 until width * height) {
            val r = data[i * 3].toInt() and 0xFF
            val g = data[i * 3 + 1].toInt() and 0xFF
            val b = data[i * 3 + 2].toInt() and 0xFF
            out.data[i] = 0.299f * r + 0.587f * g + 0.114f * b
        }
        return out
    }
}

/** Single-channel float image / probability map. */
class FloatMask(val width: Int, val height: Int, val data: FloatArray = FloatArray(width * height)) {
    operator fun get(x: Int, y: Int) = data[y * width + x]
    operator fun set(x: Int, y: Int, v: Float) {
        data[y * width + x] = v
    }

    fun copy() = FloatMask(width, height, data.copyOf())

    fun resize(w: Int, h: Int): FloatMask {
        if (w == width && h == height) return this
        val out = FloatMask(w, h)
        Resample.bilinear(data, width, height, width.toFloat() / w, height.toFloat() / h, 0f, 0f, out.data, w, 0, 0, w, h, max = false)
        return out
    }

    /** Bilinear sample with zero outside the image. */
    fun sample(x: Float, y: Float): Float {
        if (x < -1f || y < -1f || x > width || y > height) return 0f
        val fx = floor(x)
        val fy = floor(y)
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val wx = x - fx
        val wy = y - fy
        val x1 = x0 + 1
        val y1 = y0 + 1
        val d = data
        val w = width
        if (x0 >= 0 && y0 >= 0 && x1 < w && y1 < height) { // fast path: all four neighbours inside
            val r0 = y0 * w
            val r1 = r0 + w
            val top = d[r0 + x0] + (d[r0 + x1] - d[r0 + x0]) * wx
            val bot = d[r1 + x0] + (d[r1 + x1] - d[r1 + x0]) * wx
            return top + (bot - top) * wy
        }
        val okX0 = x0 >= 0 && x0 < w
        val okX1 = x1 >= 0 && x1 < w
        val okY0 = y0 >= 0 && y0 < height
        val okY1 = y1 >= 0 && y1 < height
        val a = if (okX0 && okY0) d[y0 * w + x0] else 0f
        val b = if (okX1 && okY0) d[y0 * w + x1] else 0f
        val c = if (okX0 && okY1) d[y1 * w + x0] else 0f
        val e = if (okX1 && okY1) d[y1 * w + x1] else 0f
        val top = a + (b - a) * wx
        val bot = c + (e - c) * wx
        return top + (bot - top) * wy
    }

    fun mean(): Float = if (data.isEmpty()) 0f else data.sum() / data.size
}

/** Fast bilinear resampling kernels (per-column indices and weights are computed once per call). */
object Resample {
    /**
     * Writes into the rectangle [dx0, dx1) × [dy0, dy1) of [out] (row length [outW]) the bilinear sample of
     * [src] (sw × sh) at source position ((x + offX + 0.5) · kx − 0.5, (y + offY + 0.5) · ky − 0.5),
     * clamped to the source. With [max] the result is max-combined with what [out] already holds.
     */
    fun bilinear(
        src: FloatArray, sw: Int, sh: Int, kx: Float, ky: Float, offX: Float, offY: Float,
        out: FloatArray, outW: Int, dx0: Int, dy0: Int, dx1: Int, dy1: Int, max: Boolean,
    ) {
        val n = dx1 - dx0
        if (n <= 0 || dy1 <= dy0) return
        val xi0 = IntArray(n)
        val xi1 = IntArray(n)
        val xw = FloatArray(n)
        for (i in 0 until n) {
            val fx = ((dx0 + i + offX + 0.5f) * kx - 0.5f).coerceIn(0f, sw - 1f)
            val x0 = fx.toInt()
            xi0[i] = x0
            xi1[i] = min(x0 + 1, sw - 1)
            xw[i] = fx - x0
        }
        Par.range(dy0, dy1, n) { y ->
            val fy = ((y + offY + 0.5f) * ky - 0.5f).coerceIn(0f, sh - 1f)
            val y0 = fy.toInt()
            val r0 = y0 * sw
            val r1 = min(y0 + 1, sh - 1) * sw
            val wy = fy - y0
            val o = y * outW + dx0
            for (i in 0 until n) {
                val a = src[r0 + xi0[i]]
                val b = src[r0 + xi1[i]]
                val c = src[r1 + xi0[i]]
                val d = src[r1 + xi1[i]]
                val top = a + (b - a) * xw[i]
                val v = top + (c + (d - c) * xw[i] - top) * wy
                if (!max || v > out[o + i]) out[o + i] = v
            }
        }
    }
}

/** Single-channel 8-bit mask (0..255). */
class ByteMask(val width: Int, val height: Int, val data: ByteArray = ByteArray(width * height)) {
    operator fun get(x: Int, y: Int) = data[y * width + x].toInt() and 0xFF
    operator fun set(x: Int, y: Int, v: Int) {
        data[y * width + x] = v.coerceIn(0, 255).toByte()
    }

    fun any(): Boolean = data.any { it.toInt() != 0 }
    fun copy() = ByteMask(width, height, data.copyOf())

    fun countOn(): Int = data.count { it.toInt() != 0 }

    /** Area-averaged downscale / bilinear upscale. */
    fun resize(w: Int, h: Int): ByteMask {
        if (w == width && h == height) return this
        val f = FloatMask(width, height, FloatArray(width * height) { (data[it].toInt() and 0xFF).toFloat() })
        val r = if (w < width && h < height) MaskOps.areaDownscale(f, w, h) else f.resize(w, h)
        return ByteMask(w, h, ByteArray(w * h) { (r.data[it] + 0.5f).toInt().coerceIn(0, 255).toByte() })
    }

    fun maxWith(other: ByteMask) {
        for (i in data.indices) {
            val a = data[i].toInt() and 0xFF
            val b = other.data[i].toInt() and 0xFF
            if (b > a) data[i] = b.toByte()
        }
    }

    companion object {
        fun fromBinary(m: BooleanArray, w: Int, h: Int) = ByteMask(w, h, ByteArray(w * h) { if (m[it]) -1 else 0 })
    }
}

/** Box/bounding-box helpers. Boxes are (x1, y1, x2, y2). */
data class Box(val x1: Float, val y1: Float, val x2: Float, val y2: Float) {
    val w get() = x2 - x1
    val h get() = y2 - y1
    val cx get() = (x1 + x2) / 2
    val cy get() = (y1 + y2) / 2
    val area get() = max(0f, w) * max(0f, h)
    fun contains(x: Float, y: Float) = x in x1..x2 && y in y1..y2
    fun shift(dx: Float, dy: Float) = Box(x1 + dx, y1 + dy, x2 + dx, y2 + dy)
    fun scale(sx: Float, sy: Float) = Box(x1 * sx, y1 * sy, x2 * sx, y2 * sy)
    fun lerp(to: Box, a: Float) = Box(x1 + (to.x1 - x1) * a, y1 + (to.y1 - y1) * a, x2 + (to.x2 - x2) * a, y2 + (to.y2 - y2) * a)

    /** Fraction of this box's area that lies inside [o]. */
    fun containedIn(o: Box): Float {
        val ix = max(0f, min(x2, o.x2) - max(x1, o.x1))
        val iy = max(0f, min(y2, o.y2) - max(y1, o.y1))
        return ix * iy / max(1e-6f, w * h)
    }

    fun iou(o: Box): Float {
        val ix = max(0f, min(x2, o.x2) - max(x1, o.x1))
        val iy = max(0f, min(y2, o.y2) - max(y1, o.y1))
        val inter = ix * iy
        val union = area + o.area - inter
        return if (union > 0) inter / union else 0f
    }
}

/** Greedy non-maximum suppression. Returns kept indices sorted by score. */
fun nms(boxes: List<Box>, scores: List<Float>, iouThreshold: Float): List<Int> {
    val order = scores.indices.sortedByDescending { scores[it] }
    val keep = ArrayList<Int>()
    val removed = BooleanArray(boxes.size)
    for (i in order) {
        if (removed[i]) continue
        keep += i
        for (j in order) if (!removed[j] && j != i && boxes[i].iou(boxes[j]) > iouThreshold) removed[j] = true
    }
    return keep
}
