package com.manhwalens.app.live

import com.manhwalens.app.ocr.geometry.Rect2D
import kotlin.math.floor

/**
 * Frame-diff masking for the live loop (Part C): cells covered by Arabic patches
 * are excluded from the frame-difference metric, so our own patches can never
 * read as "the page changed" and can never trigger a cycle.
 *
 * Thumbnail space: THUMB_W×THUMB_H cells covering the frame uniformly —
 * cell (cx, cy) covers frame rect
 *   [cx*fw/THUMB_W, (cx+1)*fw/THUMB_W) × [cy*fh/THUMB_H, (cy+1)*fh/THUMB_H).
 * A cell is masked when it INTERSECTS any patch rect (conservative: boundary
 * cells are excluded too — a patch touching a cell hides content there).
 */
object DiffMask {

    /**
     * Build the cell mask for [rects] (frame px). Returned mask is row-major,
     * size THUMB_W × THUMB_H. Empty input → all-false mask.
     */
    fun cellMaskForRects(
        rects: List<Rect2D>,
        frameW: Int,
        frameH: Int,
        thumbW: Int = LiveFrameMath.THUMB_W,
        thumbH: Int = LiveFrameMath.THUMB_H
    ): BooleanArray {
        val mask = BooleanArray(thumbW * thumbH)
        if (rects.isEmpty() || frameW <= 0 || frameH <= 0) return mask
        val cellW = frameW.toFloat() / thumbW
        val cellH = frameH.toFloat() / thumbH
        for (rect in rects) {
            val l = rect.left.coerceIn(0f, frameW.toFloat())
            val t = rect.top.coerceIn(0f, frameH.toFloat())
            val r = rect.right.coerceIn(0f, frameW.toFloat())
            val b = rect.bottom.coerceIn(0f, frameH.toFloat())
            if (r <= l || b <= t) continue
            // First and last intersected cells (right/bottom are exclusive bounds).
            val c0 = floor((l / cellW).toDouble()).toInt().coerceIn(0, thumbW - 1)
            val c1 = floor(((r - 1e-4f) / cellW).toDouble()).toInt().coerceIn(0, thumbW - 1)
            val r0 = floor((t / cellH).toDouble()).toInt().coerceIn(0, thumbH - 1)
            val r1 = floor(((b - 1e-4f) / cellH).toDouble()).toInt().coerceIn(0, thumbH - 1)
            for (row in r0..r1) {
                val base = row * thumbW
                for (col in c0..c1) mask[base + col] = true
            }
        }
        return mask
    }

    /** Union of two masks (either may be null). */
    fun union(a: BooleanArray?, b: BooleanArray?): BooleanArray? {
        if (a == null) return b?.copyOf()
        if (b == null) return a.copyOf()
        if (a.size != b.size) return a.copyOf()
        val out = BooleanArray(a.size)
        for (i in out.indices) out[i] = a[i] || b[i]
        return out
    }

    /** Mean abs diff over UNMASKED cells; Float.MAX_VALUE when the thumbnails are wrong-sized. */
    fun meanAbsoluteDifferenceExcluding(a: ByteArray, b: ByteArray, exclude: BooleanArray?): Float {
        if (a.size != b.size) return Float.MAX_VALUE
        if (a.isEmpty()) return 0f
        if (exclude == null) return LiveFrameMath.meanAbsoluteDifference(a, b)
        var sum = 0L
        var count = 0L
        for (i in a.indices) {
            if (i < exclude.size && exclude[i]) continue
            sum += kotlin.math.abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)).toLong()
            count++
        }
        // Nothing comparable left (everything is under our patches) — reads as "no change".
        if (count == 0L) return 0f
        return sum / count.toFloat()
    }

    /** true when the two thumbnails are nearly equal OUTSIDE the masked cells. */
    fun nearlyEqualExcluding(a: ByteArray, b: ByteArray, exclude: BooleanArray?, tolerance: Float = 2f): Boolean =
        meanAbsoluteDifferenceExcluding(a, b, exclude) < tolerance
}
