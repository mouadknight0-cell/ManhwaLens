package com.paddle.ocr.preprocess

import kotlin.math.ceil

/**
 * Shape policy used by PaddleOCR's Arabic PP-OCRv5 recognition preprocessor.
 * Crops remain aspect-preserving; a batch is padded to at least 320px width.
 */
object RecResizePolicy {

    fun contentWidth(
        sourceWidth: Int,
        sourceHeight: Int,
        targetHeight: Int = 48,
        maxWidth: Int = 3200
    ): Int {
        require(sourceWidth > 0 && sourceHeight > 0) { "Source crop dimensions must be positive" }
        require(targetHeight > 0 && maxWidth > 0) { "Recognition limits must be positive" }
        return ceil(targetHeight * sourceWidth.toDouble() / sourceHeight)
            .toInt()
            .coerceIn(1, maxWidth)
    }

    fun batchWidth(
        contentWidths: List<Int>,
        baseWidth: Int = 320,
        maxWidth: Int = 3200
    ): Int {
        require(contentWidths.isNotEmpty()) { "Recognition batch must not be empty" }
        require(baseWidth > 0 && maxWidth >= baseWidth) { "Invalid recognition width limits" }
        require(contentWidths.all { it > 0 }) { "Content widths must be positive" }
        return maxOf(baseWidth, contentWidths.maxOrNull() ?: baseWidth).coerceAtMost(maxWidth)
    }
}
