package com.manhwalens.app.render

import android.graphics.Bitmap

/**
 * One fully rendered Arabic patch for a bubble — a small bitmap limited to the
 * block's (possibly expanded) rect. Screen/frame coordinate space: [left]/[top]/
 * [right]/[bottom] are in CAPTURED-FRAME pixels; the overlay scales at draw time.
 */
data class BubblePatch(
    val id: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val bitmap: Bitmap
)
