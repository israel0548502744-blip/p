package com.blueshield.core.gender

import com.blueshield.core.image.AreaScaler
import com.blueshield.core.image.RgbImage
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/**
 * The original photo, for a closer look at faces. A photo is analysed scaled down (at most 768 px), where a face
 * may be only 60 px wide; the face models read it more reliably at the photo's own resolution (922 labelled faces
 * with a larger original: men clearly male in 61 % of single looks from the analysis frame, 85 % from the
 * original; women unchanged — 2 % called male either way).
 *
 * [width] × [height]: the original's size; [scale]: its pixels per analysis-frame pixel; [row] fills `out[0 until n]`
 * with the ARGB pixels `x until x + n` of row `y` (always inside the original).
 */
class FaceSource(
    private val width: Int,
    private val height: Int,
    private val scale: Float,
    private val row: (y: Int, x: Int, n: Int, out: IntArray) -> Unit,
) {
    /** A square cut of the original: analysis-frame point (fx, fy) is at ((fx − [x]) · [scale], (fy − [y]) · [scale]) in [image]. */
    class Square(val image: RgbImage, val x: Float, val y: Float, val scale: Float)

    /**
     * The square [x, x + side) × [y, y + side) of the analysis frame (in its pixels) cut from the original, edge pixels
     * repeated beyond its border, at the original's resolution but at most [maxPx] wide.
     */
    fun square(x: Float, y: Float, side: Float, maxPx: Int): Square {
        val ox = floor(x * scale).toInt()
        val oy = floor(y * scale).toInt()
        val s = max(1, ceil(side * scale).toInt())
        val line = IntArray(s)
        val inside = IntArray(width)
        // one row of the square: the part inside the original read, the rest the nearest edge pixel
        fun read(sy: Int, out: IntArray) {
            val yy = (oy + sy).coerceIn(0, height - 1)
            val a = ox.coerceIn(0, width - 1)
            val b = (ox + s - 1).coerceIn(0, width - 1)
            row(yy, a, b - a + 1, inside)
            for (i in 0 until s) out[i] = inside[(ox + i).coerceIn(a, b) - a]
        }
        val img = if (s <= maxPx) {
            RgbImage(s, s).also { im ->
                for (sy in 0 until s) {
                    read(sy, line)
                    for (i in 0 until s) {
                        val p = line[i]
                        val o = (sy * s + i) * 3
                        im.data[o] = (p shr 16).toByte()
                        im.data[o + 1] = (p shr 8).toByte()
                        im.data[o + 2] = p.toByte()
                    }
                }
            }
        } else {
            AreaScaler(s, s, maxPx, maxPx).scale { sy, out -> read(sy, out) }
        }
        return Square(img, ox / scale, oy / scale, img.width * scale / s)
    }
}
