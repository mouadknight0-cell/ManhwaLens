package com.manhwalens.app.render

import kotlin.math.ceil

/** Measurement of [text] laid out at [fontSizePx] within [maxWidthPx]. */
data class Measured(val heightPx: Int, val maxLineWidthPx: Int)

/**
 * Seam between the pure auto-fit algorithm and Android's text stack.
 * The production implementation uses StaticLayout with the bundled Cairo font.
 */
fun interface TextMeasurer {
    fun measure(text: String, maxWidthPx: Int, fontSizePx: Float): Measured
}

data class AutoFitResult(
    val fontSizePx: Float,
    val heightPx: Int,
    /** False when even the minimum size overflowed the box height. */
    val fits: Boolean
)

/**
 * Pick the largest font size whose wrapped layout fits [maxHeightPx].
 * Deterministic: descends from [maxPx] to [minPx] in [STEP_PX] increments.
 */
object AutoFitTextSizer {

    const val STEP_PX = 1f

    fun fit(
        text: String,
        maxWidthPx: Int,
        maxHeightPx: Int,
        minPx: Float = 6f,
        maxPx: Float = 64f,
        measurer: TextMeasurer
    ): AutoFitResult {
        if (text.isBlank() || maxWidthPx <= 0 || maxHeightPx <= 0 || maxPx <= minPx) {
            return AutoFitResult(minPx.coerceAtLeast(1f), 0, text.isBlank())
        }
        val topMeasure = measurer.measure(text, maxWidthPx, maxPx)
        if (topMeasure.heightPx <= maxHeightPx) {
            return AutoFitResult(maxPx, topMeasure.heightPx, true)
        }
        val maxSteps = kotlin.math.floor(((maxPx - minPx) / STEP_PX).toDouble()).toInt()
        if (maxSteps <= 0) {
            return AutoFitResult(minPx, topMeasure.heightPx, false)
        }
        // Binary search over k in [1..maxSteps] where size = maxPx - k * STEP_PX.
        // Smaller k means larger font size; find the smallest k that fits.
        var lo = 1
        var hi = maxSteps
        var bestSize = minPx
        var bestHeight = 0
        var found = false
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val size = maxPx - mid * STEP_PX
            val m = measurer.measure(text, maxWidthPx, size)
            if (m.heightPx <= maxHeightPx) {
                found = true
                bestSize = size
                bestHeight = m.heightPx
                hi = mid - 1 // try larger font (smaller k)
            } else {
                lo = mid + 1 // try smaller font (larger k)
            }
        }
        if (found) {
            return AutoFitResult(bestSize, bestHeight, true)
        }
        val floorSize = maxPx - maxSteps * STEP_PX
        val floorHeight = if (floorSize == minPx) {
            measurer.measure(text, maxWidthPx, minPx).heightPx
        } else {
            measurer.measure(text, maxWidthPx, minPx).heightPx
        }
        return AutoFitResult(minPx, floorHeight, false)
    }
}

/**
 * Convenience measurer used ONLY by unit tests: every glyph is [ADVANCE_RATIO]
 * em wide, line height is [LINE_HEIGHT_RATIO] em, wrapping is greedy.
 * Mirrors the production semantics closely enough to exercise the algorithm.
 */
class FakeMonospaceMeasurer(
    private val advanceRatio: Float = 0.6f,
    private val lineHeightRatio: Float = 1.3f
) : TextMeasurer {

    override fun measure(text: String, maxWidthPx: Int, fontSizePx: Float): Measured {
        val advance = fontSizePx * advanceRatio
        val maxCharsPerLine = (maxWidthPx / advance).toInt().coerceAtLeast(1)
        val words = text.split(' ')
        var lines = 1
        var current = 0
        for (word in words) {
            val needed = word.length + if (current > 0) 1 else 0
            if (current + needed > maxCharsPerLine) {
                lines++
                current = word.length
            } else {
                current += needed
            }
        }
        val height = ceil(lines * fontSizePx * lineHeightRatio).toInt()
        val maxLineW = (maxCharsPerLine * advance).toInt()
        return Measured(height, maxLineW)
    }
}
