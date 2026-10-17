package com.blueshield.app.engine

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A stroke painted by hand on a censored photo: [points] in 0..1 of the photo's width and height, [radius] in
 * 0..1 of its width. [erase] = take the cover away (the original shows), otherwise cover with the censor colour.
 * Later strokes are drawn over earlier ones.
 */
class BrushStroke(val points: List<Pair<Float, Float>>, val radius: Float, val erase: Boolean) {
    companion object {
        /** Draws [strokes] into [px] (the censored photo); [orig] is the photo before censoring; edges are softened over ~1.5 px. */
        fun apply(strokes: List<BrushStroke>, px: IntArray, orig: IntArray, w: Int, h: Int, rgb: Int) {
            val colour = rgb or (0xFF shl 24)
            for (s in strokes) {
                val r = max(1f, s.radius * w)
                var prev: Pair<Float, Float>? = null
                for (p in s.points) {
                    val x1 = p.first * w
                    val y1 = p.second * h
                    val (x0, y0) = prev?.let { it.first * w to it.second * h } ?: (x1 to y1)
                    prev = p
                    // the segment as a capsule: every pixel within r of it
                    val minX = max(0, floor(min(x0, x1) - r - 1).toInt())
                    val maxX = min(w - 1, ceil(max(x0, x1) + r + 1).toInt())
                    val minY = max(0, floor(min(y0, y1) - r - 1).toInt())
                    val maxY = min(h - 1, ceil(max(y0, y1) + r + 1).toInt())
                    val dx = x1 - x0
                    val dy = y1 - y0
                    val len2 = dx * dx + dy * dy
                    for (y in minY..maxY) for (x in minX..maxX) {
                        val t = if (len2 == 0f) 0f else (((x - x0) * dx + (y - y0) * dy) / len2).coerceIn(0f, 1f)
                        val d = hypot(x - (x0 + t * dx), y - (y0 + t * dy))
                        if (d > r + 0.75f) continue
                        val a = ((r + 0.75f - d) / 1.5f).coerceIn(0f, 1f)
                        val i = y * w + x
                        val target = if (s.erase) orig[i] else colour
                        px[i] = if (a >= 1f) target else blend(px[i], target, a)
                    }
                }
            }
        }

        private fun blend(a: Int, b: Int, t: Float): Int {
            fun ch(sh: Int) = ((a shr sh and 0xFF) * (1 - t) + (b shr sh and 0xFF) * t).toInt()
            return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }
}
