package com.paddle.ocr.preprocess

import com.paddle.ocr.util.MathUtils

/** Output dimensions for the detector input after limit policy and 32px alignment. */
data class DetResizeDimensions(val width: Int, val height: Int)

/**
 * Pure image-size calculation shared by detector preprocessing and JVM regression tests.
 * "min" preserves small text on tall phone screenshots while maxSideLimit prevents runaway tensors.
 */
object DetResizeCalculator {

    fun calculate(
        originalWidth: Int,
        originalHeight: Int,
        limitSideLen: Int,
        limitType: String,
        maxSideLimit: Int
    ): DetResizeDimensions {
        require(originalWidth > 0 && originalHeight > 0) { "Image dimensions must be positive" }
        require(limitSideLen > 0 && maxSideLimit > 0) { "Resize limits must be positive" }

        var ratio = when (limitType.lowercase()) {
            "max" -> if (maxOf(originalHeight, originalWidth) > limitSideLen) {
                limitSideLen.toDouble() / maxOf(originalHeight, originalWidth)
            } else 1.0
            "min" -> if (minOf(originalHeight, originalWidth) < limitSideLen) {
                limitSideLen.toDouble() / minOf(originalHeight, originalWidth)
            } else 1.0
            "resize_long" -> limitSideLen.toDouble() / maxOf(originalHeight, originalWidth)
            else -> throw IllegalArgumentException("Unsupported det limit type: $limitType")
        }

        var height = (originalHeight * ratio).toInt()
        var width = (originalWidth * ratio).toInt()
        if (maxOf(height, width) > maxSideLimit) {
            ratio = maxSideLimit.toDouble() / maxOf(height, width)
            height = (height * ratio).toInt()
            width = (width * ratio).toInt()
        }

        height = maxOf(MathUtils.roundHalfToEven(height / 32.0) * 32, 32)
        width = maxOf(MathUtils.roundHalfToEven(width / 32.0) * 32, 32)
        return DetResizeDimensions(width, height)
    }
}
