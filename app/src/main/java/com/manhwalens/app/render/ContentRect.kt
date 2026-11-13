package com.manhwalens.app.render

/**
 * Pure-math mapping between a Bitmap's pixel space and a ContentScale.Fit
 * rect inside a square view slot. Unit-testable on the JVM.
 */
data class ContentRect(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val contentWidth: Float,
    val contentHeight: Float
) {
    /** Map a bitmap-space box (pixels) to view-space pixels. */
    fun mapBox(left: Int, top: Int, right: Int, bottom: Int): FloatArray =
        floatArrayOf(
            offsetX + left * scale,
            offsetY + top * scale,
            offsetX + right * scale,
            offsetY + bottom * scale
        )
}

/**
 * ContentScale.Fit: scale by the smaller axis, center on the larger axis.
 * Returns a zero ContentRect when either side has zero size.
 */
fun computeContentRect(viewWidthPx: Float, viewHeightPx: Float, bmpWidth: Int, bmpHeight: Int): ContentRect {
    if (viewWidthPx <= 0f || viewHeightPx <= 0f || bmpWidth <= 0 || bmpHeight <= 0) {
        return ContentRect(0f, 0f, 0f, 0f, 0f)
    }
    val scale = minOf(viewWidthPx / bmpWidth, viewHeightPx / bmpHeight)
    val contentWidth = bmpWidth * scale
    val contentHeight = bmpHeight * scale
    return ContentRect(
        scale = scale,
        offsetX = (viewWidthPx - contentWidth) / 2f,
        offsetY = (viewHeightPx - contentHeight) / 2f,
        contentWidth = contentWidth,
        contentHeight = contentHeight
    )
}
