package com.manhwalens.app.live

/**
 * Which reason should the trace log when the live loop clears patches because the
 * frame changed? "scroll" when consecutive thumbnails look like the SAME sequence of
 * rows shifted vertically (RowHashShift matched), otherwise "page change".
 * Pure Kotlin — unit-testable.
 */
object LiveRemovalReason {

    fun forFrameChange(prevGray: ByteArray?, gray: ByteArray): String {
        if (prevGray == null) return "page change"
        val shift = RowHashShift.estimate(prevGray, gray, LiveFrameMath.THUMB_W, LiveFrameMath.THUMB_H)
        return if (shift != null) "scroll" else "page change"
    }
}
