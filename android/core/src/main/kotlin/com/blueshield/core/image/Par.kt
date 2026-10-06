package com.blueshield.core.image

import java.util.stream.IntStream

/**
 * Row-parallel loops for the per-pixel kernels (warping, resampling, optical flow…). Each row writes
 * only its own output, so results are identical to the sequential loop — just spread over all cores.
 * Small jobs stay on the calling thread (forking costs more than it saves).
 */
object Par {
    inline fun rows(height: Int, width: Int, crossinline body: (Int) -> Unit) {
        if (height < 2 || height.toLong() * width < 24_000L) {
            for (y in 0 until height) body(y)
        } else {
            IntStream.range(0, height).parallel().forEach { body(it) }
        }
    }

    /** Like [rows] for the half-open range [from, until). */
    inline fun range(from: Int, until: Int, width: Int, crossinline body: (Int) -> Unit) {
        val n = until - from
        if (n < 2 || n.toLong() * width < 24_000L) {
            for (y in from until until) body(y)
        } else {
            IntStream.range(from, until).parallel().forEach { body(it) }
        }
    }
}
