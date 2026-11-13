package com.manhwalens.app.live

import com.manhwalens.app.ocr.geometry.Rect2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDiffMaskTest {

    private val W = 64
    private val H = 128

    private fun thumbUniform(v: Int) = ByteArray(W * H) { v.toByte() }

    /** Deterministic pseudo-random thumbnail. */
    private fun thumbPattern(seed: Long): ByteArray {
        var x = seed
        return ByteArray(W * H) {
            x = x * 6364136223846793005L + 1442695040888963407L
            ((x ushr 33) and 0xFF).toByte()
        }
    }

    // ---------- cellMaskForRects ----------

    @Test
    fun emptyRects_maskAllFalse() {
        val mask = DiffMask.cellMaskForRects(emptyList(), 640, 1280)
        assertEquals(W * H, mask.size)
        assertTrue(mask.none { it })
    }

    @Test
    fun fullCoverRect_masksEverything() {
        val mask = DiffMask.cellMaskForRects(listOf(Rect2D(0f, 0f, 640f, 1280f)), 640, 1280)
        assertTrue(mask.all { it })
    }

    @Test
    fun exactCellAlignment_masksExactCells() {
        // frame 640×1280 → cell = 10×10 px exactly
        val mask = DiffMask.cellMaskForRects(listOf(Rect2D(0f, 0f, 50f, 40f)), 640, 1280)
        // cells (col 0..4, row 0..3)
        for (row in 0..3) for (col in 0..4) assertTrue("cell($col,$row)", mask[row * W + col])
        assertFalse(mask[0 * W + 5])
        assertFalse(mask[4 * W + 0])
    }

    @Test
    fun partialCellIntersection_masksBoundaryCell() {
        val mask = DiffMask.cellMaskForRects(listOf(Rect2D(5f, 5f, 15f, 15f)), 640, 1280)
        assertTrue(mask[0])                      // (0,0) intersected
        assertTrue(mask[1])                      // (1,0) intersected
        assertTrue(mask[W + 1])                  // (1,1) intersected
        assertFalse(mask[2])
    }

    @Test
    fun rectEndingExactlyOnCellBoundary_doesNotMaskNextCell() {
        // right edge exactly at x=10 (10px cells) → cell col 1 must NOT be masked
        val mask = DiffMask.cellMaskForRects(listOf(Rect2D(0f, 0f, 10f, 10f)), 640, 1280)
        assertTrue(mask[0])
        assertFalse(mask[1])
    }

    @Test
    fun outOfFrameRect_isClampedNotCrashing() {
        val mask = DiffMask.cellMaskForRects(listOf(Rect2D(-50f, -50f, 10000f, 20f)), 640, 1280, thumbW = W, thumbH = H)
        assertTrue(mask[0])
        assertFalse(mask[127 * W + 63])
    }

    // ---------- meanAbsoluteDifferenceExcluding ----------

    @Test
    fun diffExcluding_ignoresOnlyMaskedCells() {
        val a = thumbUniform(10)
        val b = thumbUniform(10)
        for (i in b.indices) if (i % 7 == 0) b[i] = 250.toByte()  // differences all over
        val mask = BooleanArray(W * H) { i -> i % 7 == 0 }        // mask exactly those cells
        assertEquals(0f, DiffMask.meanAbsoluteDifferenceExcluding(a, b, mask), 0.001f)
        val changedCells = (0 until (W * H)).count { it % 7 == 0 }
        assertEquals(changedCells * 240f / (W * H), DiffMask.meanAbsoluteDifferenceExcluding(a, b, null), 0.001f)
    }

    @Test
    fun diffExcluding_everythingMasked_readsAsNoChange() {
        val a = thumbUniform(0)
        val b = thumbUniform(255)
        assertEquals(0f, DiffMask.meanAbsoluteDifferenceExcluding(a, b, BooleanArray(W * H) { true }), 0f)
    }

    @Test
    fun diffExcluding_mismatchedSize_isMaxValue() {
        assertEquals(Float.MAX_VALUE, DiffMask.meanAbsoluteDifferenceExcluding(ByteArray(4), ByteArray(8), null))
    }

    @Test
    fun nearlyEqualExcluding_respectsTolerance() {
        val a = thumbUniform(100)
        val b = thumbUniform(100)
        b[0] = 101.toByte()
        assertTrue(DiffMask.nearlyEqualExcluding(a, b, null))
        val c = thumbUniform(140)
        assertFalse(DiffMask.nearlyEqualExcluding(a, c, null))
    }

    // ---------- union ----------

    @Test
    fun union_handlesNullsAndMismatch() {
        assertNull(DiffMask.union(null, null))
        val a = BooleanArray(4) { true }
        assertEquals(listOf(true, true, true, true), DiffMask.union(a, null)!!.toList())
        val b = BooleanArray(4) { i -> i == 2 }
        assertEquals(listOf(true, true, true, true), DiffMask.union(a, b)!!.toList())
        // Mismatched sizes: union keeps the first mask (defensive; callers always pass same-size masks)
        assertArrayEqualsCompat(BooleanArray(4) { false }, DiffMask.union(BooleanArray(4), BooleanArray(2) { true })!!)
    }

    private fun assertArrayEqualsCompat(expected: BooleanArray, actual: BooleanArray) {
        assertEquals(expected.size, actual.size)
        for (i in expected.indices) assertEquals("idx=$i", expected[i], actual[i])
    }

    // ---------- LiveStabilityTracker with excludeMask ----------

    @Test
    fun tracker_changesOnlyUnderPatches_neverReadsAsChanging() {
        val tracker = LiveStabilityTracker(changeThreshold = 10f, stableWindowMs = 400L)
        val base = thumbPattern(42L)
        tracker.excludeMask = null
        assertEquals(LiveStabilityTracker.Verdict.Changing, tracker.onFrame(base, 0L))      // first frame seeds
        // Next frame differs WILDLY but only inside the masked (patch) cells
        val modified = base.copyOf()
        val mask = DiffMask.cellMaskForRects(listOf(Rect2D(0f, 0f, 200f, 300f)), 640, 1280, thumbW = W, thumbH = H)
        for (i in modified.indices) if (mask[i]) modified[i] = (modified[i] + 120).toByte()
        tracker.excludeMask = mask
        val verdict = tracker.onFrame(modified, 5_000L)
        assertEquals(LiveStabilityTracker.Verdict.Stable, verdict)
    }

    @Test
    fun tracker_changesOutsidePatches_readsAsChanging() {
        val tracker = LiveStabilityTracker(changeThreshold = 10f, stableWindowMs = 400L)
        val base = thumbPattern(42L)
        tracker.onFrame(base, 0L)
        val modified = base.copyOf()
        val mask = DiffMask.cellMaskForRects(listOf(Rect2D(0f, 0f, 100f, 100f)), 640, 1280, thumbW = W, thumbH = H)
        // Rewrite the whole frame EXCEPT the mask — that's an obvious page change
        for (i in modified.indices) if (!mask[i]) modified[i] = (modified[i] + 200).toByte()
        tracker.excludeMask = mask
        assertEquals(LiveStabilityTracker.Verdict.Changing, tracker.onFrame(modified, 5_000L))
    }

    // ---------- LiveRemovalReason ----------

    @Test
    fun removalReason_nullPrev_isPageChange() {
        assertEquals("page change", LiveRemovalReason.forFrameChange(null, thumbUniform(7)))
    }

    private fun rowConstantThumb(rowValues: IntArray): ByteArray {
        val out = ByteArray(W * H)
        for (y in 0 until H) {
            val v = (rowValues[y % rowValues.size] and 0xFF).toByte()
            for (x in 0 until W) out[y * W + x] = v
        }
        return out
    }

    @Test
    fun removalReason_shiftedFrame_isScroll() {
        val rows = IntArray(H) { (it * 37 + 11) and 0xFF }
        val prev = rowConstantThumb(rows)
        // curr[i] ≈ prev[i + shift] for shift=3 (user scrolled down 3 rows)
        val shifted = IntArray(H) { y -> if (y + 3 < H) rows[y + 3] else 128 }
        val curr = rowConstantThumb(shifted)
        assertEquals("scroll", LiveRemovalReason.forFrameChange(prev, curr))
    }

    @Test
    fun removalReason_unrelatedFrame_isPageChange() {
        val prev = thumbPattern(123L)
        val curr = thumbPattern(987L)
        assertEquals("page change", LiveRemovalReason.forFrameChange(prev, curr))
    }
}
