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
    fun mask(img: RgbImage): BooleanArray =
        mask(img.width, img.height) { i -> ((img.data[i * 3].toInt() and 0xFF) shl 16) or ((img.data[i * 3 + 1].toInt() and 0xFF) shl 8) or (img.data[i * 3 + 2].toInt() and 0xFF) }

    /** Same for ARGB [pixels] (a full-size photo: no extra copy of it). */
    fun mask(pixels: IntArray, w: Int, h: Int): BooleanArray = mask(w, h) { pixels[it] }

    private inline fun mask(w: Int, h: Int, crossinline rgb: (Int) -> Int): BooleanArray {
        // per pixel only the two facts the test needs: "bright and unsaturated", "dark"
        val bright = BooleanArray(w * h)
        val dark = BooleanArray(w * h)
        Par.rows(h, w) { y ->
            for (i in y * w until (y + 1) * w) {
                val c = rgb(i)
                val r = (c shr 16 and 0xFF) / 255f
                val g = (c shr 8 and 0xFF) / 255f
                val b = (c and 0xFF) / 255f
                val l = 0.299f * r + 0.587f * g + 0.114f * b
                val s = maxOf(r, g, b) - minOf(r, g, b)
                bright[i] = l > BRIGHT && s < MAX_SAT
                dark[i] = l < DARK
            }
        }
        val out = BooleanArray(w * h)
        Par.rows(h, w) { y ->
            for (x in 0 until w) {
                val i = y * w + x
                if (!bright[i] && !dark[i]) continue
                for (d in dirs) {
                    val j = (y + d[1]).coerceIn(0, h - 1) * w + (x + d[0]).coerceIn(0, w - 1)
                    if ((bright[i] && dark[j]) || (dark[i] && bright[j])) {
                        out[i] = true
                        break
                    }
                }
            }
        }
        return out
    }
}
