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
 * Deterministic: the size grid is [maxPx], [maxPx]-[STEP_PX], ... down to [minPx].
 *
 * v0.13.0 — binary search over that grid instead of a 1 px linear descent. The
 * "fits" predicate is monotone in font size (a larger font never produces a shorter
 * layout: glyphs get wider, lines get taller, wrapping only adds lines), so the
 * largest fitting size found by binary search is EXACTLY the size the linear descent
 * returned — same font, same height, same [AutoFitResult.fits] — at ~6 measures
 * instead of up to ~58. That is ~6 StaticLayouts per patch instead of ~55, on the
 * overlay's critical paint path.
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
        // Grid index i in [0..steps] -> size = maxPx - i*STEP_PX. Binary-search the
        // smallest i (largest size) whose layout fits.
        val steps = ((maxPx - minPx) / STEP_PX).toInt().coerceAtLeast(0)
        var lo = 0
        var hi = steps
        var best: AutoFitResult? = null
        var minHeight = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val size = maxPx - mid * STEP_PX
            val m = measurer.measure(text, maxWidthPx, size)
            if (mid == steps) minHeight = m.heightPx
            if (m.heightPx <= maxHeightPx) {
                best = AutoFitResult(size, m.heightPx, true)
                hi = mid - 1 // a larger size might still fit
            } else {
                lo = mid + 1
            }
        }
        // No fitting size: report the floor size with its measured height, exactly like
        // the old linear descent did. (When nothing fits, the search always measures
        // the floor index, so minHeight is real.)
        return best ?: AutoFitResult(minPx, minHeight, false)
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
