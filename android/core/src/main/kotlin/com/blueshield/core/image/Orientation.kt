package com.blueshield.core.image

/**
 * Coordinate mapping between a video's *coded* frame and its *display* (upright) frame for
 * a clockwise display rotation of 0/90/180/270°.
 *
 * Analysis frames are made upright on the CPU ([displayToCoded], used by the Android
 * FrameExtractor), while the GPU compositor draws in coded orientation and looks up the
 * upright mask with [codedToDisplayUv] (mirrored line-for-line in the GLSL shader in
 * app/…/engine/Gl.kt). The two must be exact inverses — see CoreTest.
 */
object Orientation {
    /** Integer pixel mapping: display (dx, dy) → coded (x, y). */
    fun displayToCoded(dx: Int, dy: Int, rotation: Int, codedW: Int, codedH: Int): Pair<Int, Int> = when (rotation) {
        90 -> dy to (codedH - 1 - dx)
        180 -> (codedW - 1 - dx) to (codedH - 1 - dy)
        270 -> (codedW - 1 - dy) to dx
        else -> dx to dy
    }

    /** Normalised mapping used by the shader: coded uv (origin top-left) → display uv. */
    fun codedToDisplayUv(u: Float, v: Float, rotation: Int): Pair<Float, Float> = when (rotation) {
        90 -> (1f - v) to u
        180 -> (1f - u) to (1f - v)
        270 -> v to (1f - u)
        else -> u to v
    }
}
