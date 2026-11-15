package com.manhwalens.app.live

import com.manhwalens.app.ocr.geometry.Rect2D

/**
 * v0.9.12 — decision for patches left by a tap capture while Live is OFF: the screen under them
 * moved (scroll, page change, app switch) when the masked mean luma difference between the frame
 * seen after painting and the newest frame exceeds the Live change threshold.
 */
object TapPatchWatch {
    fun shouldClear(maskedDiff: Float, changeThreshold: Float): Boolean =
        !maskedDiff.isNaN() && maskedDiff > changeThreshold
}

/**
 * v0.9.12 — is an OCR block our own UI? True when its centre lies inside one of [ownRects]
 * (frame px) or at least [MIN_OVERLAP] of its area is covered by one of them.
 */
object OwnUiFilter {
    const val MIN_OVERLAP = 0.3f

    fun isOwnUi(block: Rect2D, ownRects: List<Rect2D>): Boolean {
        val area = (block.right - block.left) * (block.bottom - block.top)
        if (area <= 0f) return false
        val cx = (block.left + block.right) / 2f
        val cy = (block.top + block.bottom) / 2f
        for (r in ownRects) {
            if (cx >= r.left && cx <= r.right && cy >= r.top && cy <= r.bottom) return true
            val w = minOf(block.right, r.right) - maxOf(block.left, r.left)
            val h = minOf(block.bottom, r.bottom) - maxOf(block.top, r.top)
            if (w > 0f && h > 0f && w * h / area >= MIN_OVERLAP) return true
        }
        return false
    }
}
