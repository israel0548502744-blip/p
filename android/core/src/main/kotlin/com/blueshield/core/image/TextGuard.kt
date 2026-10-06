package com.blueshield.core.image

/**
 * Burned-in captions and on-screen text must never be hidden by the censor colour. Caption text is
 * almost always near-white with a dark outline (or dark text on a bright box), so a pixel is "text" when
 * it is very bright and unsaturated with a very dark pixel [R] px away — or very dark with a bright,
 * unsaturated one. The GPU shader (app/…/engine/Gl.kt) and the desktop renderer apply the same test.
 */
object TextGuard {
    const val R = 2
    const val BRIGHT = 0.80f
    const val DARK = 0.25f
    const val MAX_SAT = 0.25f

    private val dirs = arrayOf(intArrayOf(R, 0), intArrayOf(-R, 0), intArrayOf(0, R), intArrayOf(0, -R),
        intArrayOf(R, R), intArrayOf(-R, R), intArrayOf(R, -R), intArrayOf(-R, -R))

    /** true where the pixel belongs to on-screen text (keep it visible). */
    fun mask(img: RgbImage): BooleanArray {
        val w = img.width
        val h = img.height
        val l = FloatArray(w * h)
        val s = FloatArray(w * h)
        for (i in 0 until w * h) {
            val r = (img.data[i * 3].toInt() and 0xFF) / 255f
            val g = (img.data[i * 3 + 1].toInt() and 0xFF) / 255f
            val b = (img.data[i * 3 + 2].toInt() and 0xFF) / 255f
            l[i] = 0.299f * r + 0.587f * g + 0.114f * b
            s[i] = maxOf(r, g, b) - minOf(r, g, b)
        }
        val out = BooleanArray(w * h)
        Par.rows(h, w) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                val bright = l[i] > BRIGHT && s[i] < MAX_SAT
                val dark = l[i] < DARK
                if (!bright && !dark) continue
                for (d in dirs) {
                    val xx = (x + d[0]).coerceIn(0, w - 1)
                    val yy = (y + d[1]).coerceIn(0, h - 1)
                    val j = yy * w + xx
                    if ((bright && l[j] < DARK) || (dark && l[j] > BRIGHT && s[j] < MAX_SAT)) {
                        out[i] = true
                        break
                    }
                }
            }
        }
        return out
    }
}
