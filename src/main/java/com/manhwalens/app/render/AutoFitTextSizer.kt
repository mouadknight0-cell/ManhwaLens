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
        var size = maxPx
        var lastHeight = 0
        while (size >= minPx) {
            val m = measurer.measure(text, maxWidthPx, size)
            lastHeight = m.heightPx
            if (m.heightPx <= maxHeightPx) {
                return AutoFitResult(size, m.heightPx, true)
            }
            size -= STEP_PX
        }
        return AutoFitResult(minPx, lastHeight, false)
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
