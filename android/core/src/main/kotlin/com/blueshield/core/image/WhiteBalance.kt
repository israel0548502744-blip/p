package com.blueshield.core.image

import kotlin.math.max
import kotlin.math.min

/**
 * The colour cast of the light in a frame (grey world: the scene averages to grey), as per-channel gains that
 * undo it. Only for the skin model's input — under a purple or orange stage light skin no longer looks like skin
 * to it; the picture itself is never changed. Null when the cast is weak (most photos), so ordinary light is left
 * exactly as it was.
 */
object WhiteBalance {
    /** Gains are kept within this factor of 1 (a strongly coloured scene is not grey on average). */
    private const val MAX_GAIN = 1.33f
    /** Below this spread between the largest and smallest gain there is no cast worth undoing. */
    private const val MIN_CAST = 1.08f

    fun grayWorld(img: RgbImage): FloatArray? {
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var n = 0
        val d = img.data
        // every 4th pixel; near-black and blown-out pixels say nothing about the light
        var i = 0
        while (i < img.width * img.height) {
            val pr = d[i * 3].toInt() and 0xFF
            val pg = d[i * 3 + 1].toInt() and 0xFF
            val pb = d[i * 3 + 2].toInt() and 0xFF
            val mx = max(pr, max(pg, pb))
            if (mx in 20..250) {
                r += pr; g += pg; b += pb; n++
            }
            i += 4
        }
        if (n < 100) return null
        val grey = (r + g + b) / 3
        val gains = floatArrayOf((grey / r).toFloat(), (grey / g).toFloat(), (grey / b).toFloat()).map { it.coerceIn(1f / MAX_GAIN, MAX_GAIN) }
        if (gains.max() / gains.min() < MIN_CAST) return null
        return gains.toFloatArray()
    }
}
