package com.manhwalens.app.util

/**
 * Pure check for FLAG_SECURE-style black frames (captured screenshots of a
 * protected page come back uniformly black). Samples a 3×3 grid of pixels at
 * 1/8, 4/8 and 7/8 of each axis — the same sampling the service always used.
 *
 * Pure Kotlin on raw ARGB ints so it is unit-testable on the JVM.
 */
object FrameDarkness {

    const val DARK_LUMA = 32
    const val MIN_DARK_SAMPLES = 8
    const val GRID = 3

    /** Grid sample offsets as fractions of width/height. */
    val SAMPLE_FRACTIONS = intArrayOf(1, 4, 7) // over 8

    /** @return true when at least 8 of the 9 grid samples are darker than [DARK_LUMA]. */
    fun isNearlyBlackPixels(pixels: IntArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return false
        return isNearlyBlackSamples(sampleGrid(pixels, width, height))
    }

    /** Sample the 3×3 grid straight out of a raw ARGB buffer. */
    fun sampleGrid(pixels: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray(GRID * GRID)
        var i = 0
        for (fy in SAMPLE_FRACTIONS) {
            for (fx in SAMPLE_FRACTIONS) {
                val x = (width * fx) / 8
                val y = (height * fy) / 8
                out[i++] = pixels[y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)]
            }
        }
        return out
    }

    /** A protected (FLAG_SECURE) frame is PURE black — not merely dark. */
    const val PURE_BLACK_LUMA = 6
    /** Share of samples that may be non-black in a protected frame (our own lens button, noise). */
    const val MAX_NON_BLACK_SHARE = 0.003
    /** Dense grid used by [isProtectedBlack] callers (per axis). */
    const val DENSE_GRID = 40

    /** Number of samples brighter than [PURE_BLACK_LUMA]. */
    fun nonBlackCount(samples: IntArray): Int {
        var n = 0
        for (c in samples) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if (maxOf(r, g, b) > PURE_BLACK_LUMA) n++
        }
        return n
    }

    /**
     * v0.9.11: true only when a DENSE sample of the frame is pure black (≤ 0.3 % of samples
     * brighter than [PURE_BLACK_LUMA]). The old 3×3 check ("8 of 9 samples darker than 32")
     * called every dark-themed page or dark panel a protected frame and skipped the whole
     * pipeline. A wrong "not black" only costs one OCR pass that finds nothing; a wrong
     * "black" costs the entire translation — so this errs toward "not black".
     */
    fun isProtectedBlack(samples: IntArray): Boolean {
        if (samples.isEmpty()) return false
        return nonBlackCount(samples) <= (samples.size * MAX_NON_BLACK_SHARE).toInt()
    }

    /** @return true when at least [MIN_DARK_SAMPLES] of the 9 grid samples are near-black. */
    fun isNearlyBlackSamples(grid: IntArray): Boolean {
        var darkCount = 0
        for (c in grid) {
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            if ((r + g + b) / 3 < DARK_LUMA) darkCount++
        }
        return darkCount >= MIN_DARK_SAMPLES
    }
}
