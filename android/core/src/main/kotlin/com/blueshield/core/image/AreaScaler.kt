package com.blueshield.core.image

/**
 * Shrinks an image fed one source row at a time: every output pixel is the exact mean of the source area it covers
 * (what ffmpeg's "area" scaler computes, the path the photo tests decode with). Bilinear sampling instead skips most
 * source pixels at a large reduction (a 12 MP photo to the analysis size), so thin edges and fingers come out
 * differently. Memory: one source row and two output rows of sums, however large the source.
 */
class AreaScaler(private val srcW: Int, private val srcH: Int, val dstW: Int, val dstH: Int) {
    init {
        require(dstW in 1..srcW && dstH in 1..srcH) { "area scaling only shrinks: ${srcW}x$srcH -> ${dstW}x$dstH" }
    }

    // per source column: the output column it starts in and its weight there (the rest goes to the next column)
    private val col = IntArray(srcW)
    private val colW = FloatArray(srcW)
    private val colRest = FloatArray(srcW)

    init {
        for (x in 0 until srcW) {
            // source column x covers [x, x + 1) * dstW / srcW in output columns; in units of 1 / srcW:
            val a = x.toLong() * dstW
            val b = a + dstW
            val d = (a / srcW).toInt()
            val edge = (d + 1).toLong() * srcW
            val inFirst = (if (b <= edge) b else edge) - a
            col[x] = d
            colW[x] = inFirst.toFloat() / srcW
            colRest[x] = (dstW - inFirst).toFloat() / srcW
        }
    }

    /** [row] fills one source row ([y], ARGB, srcW pixels) into the array it is given; rows are asked for in order. */
    fun scale(row: (y: Int, argb: IntArray) -> Unit): RgbImage {
        val out = RgbImage(dstW, dstH)
        val px = IntArray(srcW)
        val line = FloatArray(dstW * 3) // one source row, reduced horizontally
        var cur = FloatArray(dstW * 3)
        var next = FloatArray(dstW * 3)
        var curRow = 0
        fun flush() {
            val o = curRow * dstW * 3
            for (i in 0 until dstW * 3) out.data[o + i] = (cur[i] + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        for (y in 0 until srcH) {
            row(y, px)
            java.util.Arrays.fill(line, 0f)
            for (x in 0 until srcW) {
                val p = px[x]
                val r = (p shr 16 and 0xFF).toFloat()
                val g = (p shr 8 and 0xFF).toFloat()
                val b = (p and 0xFF).toFloat()
                val d = col[x] * 3
                val w = colW[x]
                line[d] += r * w
                line[d + 1] += g * w
                line[d + 2] += b * w
                val rest = colRest[x]
                if (rest > 0f) {
                    line[d + 3] += r * rest
                    line[d + 4] += g * rest
                    line[d + 5] += b * rest
                }
            }
            // source row y covers [y, y + 1) * dstH / srcH in output rows
            val a = y.toLong() * dstH
            val bEnd = a + dstH
            val dy = (a / srcH).toInt()
            val edge = (dy + 1).toLong() * srcH
            val inFirst = (if (bEnd <= edge) bEnd else edge) - a
            val w0 = inFirst.toFloat() / srcH
            val w1 = (dstH - inFirst).toFloat() / srcH
            while (dy > curRow) {
                flush()
                val t = cur; cur = next; next = t
                java.util.Arrays.fill(next, 0f)
                curRow++
            }
            for (i in 0 until dstW * 3) cur[i] += line[i] * w0
            if (w1 > 0f) for (i in 0 until dstW * 3) next[i] += line[i] * w1
        }
        flush()
        return out
    }
}
