package com.manhwalens.app.ocr

import kotlin.math.max

/**
 * Pure math for OCR downscaling — JVM-testable, no Android deps.
 * Large frames are expensive for ML Kit; OCR text is still detected fine at
 * ~1600 px on the long edge. Callers scale the detected boxes back up.
 */
object BitmapScaler {

    const val DEFAULT_MAX_DIM = 1600

    /**
     * Target size for [srcW]x[srcH] bounded so the longest edge ≤ [maxDim].
     * Never upscales. Returns the original size when already within bounds.
     */
    fun computeTargetSize(srcW: Int, srcH: Int, maxDim: Int = DEFAULT_MAX_DIM): Pair<Int, Int> {
        if (srcW <= 0 || srcH <= 0 || maxDim <= 0) return srcW to srcH
        val longest = max(srcW, srcH)
        if (longest <= maxDim) return srcW to srcH
        val scale = maxDim.toFloat() / longest
        return max(1, (srcW * scale).toInt()) to max(1, (srcH * scale).toInt())
    }

    /** Inverse of the constrain factor: divide scaled coordinates to get original-space values. */
    fun inverseScale(srcW: Int, srcH: Int, scaledW: Int, scaledH: Int): Float {
        if (scaledW <= 0) return 1f
        return srcW.toFloat() / scaledW
    }
}
