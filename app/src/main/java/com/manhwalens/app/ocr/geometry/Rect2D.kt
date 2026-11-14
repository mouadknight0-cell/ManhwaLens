package com.manhwalens.app.ocr.geometry

import kotlin.math.max
import kotlin.math.min

/**
 * Plain-Kotlin bounding box used by the OCR → bubble-grouping pipeline.
 * Intentionally free of Android types so the grouper is unit-testable on JVM.
 */
data class Rect2D(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** Horizontal overlap in pixels (0 when disjoint horizontally). */
    fun horizontalOverlap(other: Rect2D): Float =
        max(0f, min(right, other.right) - max(left, other.left))

    /** Smallest box containing both. */
    fun union(other: Rect2D): Rect2D =
        Rect2D(
            left = min(left, other.left),
            top = min(top, other.top),
            right = max(right, other.right),
            bottom = max(bottom, other.bottom)
        )

    companion object {
        fun unionAll(boxes: List<Rect2D>): Rect2D {
            require(boxes.isNotEmpty()) { "unionAll requires at least one box" }
            return boxes.reduce { acc, b -> acc.union(b) }
        }
    }
}
