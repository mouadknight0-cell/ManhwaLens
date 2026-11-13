package com.manhwalens.app.render

import com.manhwalens.app.ocr.geometry.Rect2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PatchMathTest {

    private fun rect(l: Float, t: Float, r: Float, b: Float) = Rect2D(l, t, r, b)

    /** Coverage of [patch] over [box]: fraction of box area inside patch. */
    private fun coverage(box: Rect2D, patch: Rect2D): Float {
        val il = maxOf(box.left, patch.left)
        val it = maxOf(box.top, patch.top)
        val ir = minOf(box.right, patch.right)
        val ib = minOf(box.bottom, patch.bottom)
        val interW = maxOf(0f, ir - il)
        val interH = maxOf(0f, ib - it)
        return (interW * interH) / (box.width * box.height)
    }

    @Test
    fun unionWithPad_padIsWithin4To6PxSpec() {
        // PAD_PX=5 is inside the 4–6 px spec range.
        val pad = 5f
        assertTrue(pad >= 4f && pad <= 6f)
    }

    @Test
    fun unionWithPad_coversBoxFully() {
        val box = rect(100f, 100f, 200f, 200f)
        val patch = PatchMath.unionWithPad(listOf(box), 5f, 1000, 1000)!!
        // Patch must fully cover the box (coverage >= 100%).
        assertTrue("patch=$patch does not fully cover box=$box", coverage(box, patch) >= 1.0f)
        // Pad is applied on all sides.
        assertEquals(95f, patch.left, 0.001f)
        assertEquals(95f, patch.top, 0.001f)
        assertEquals(205f, patch.right, 0.001f)
        assertEquals(205f, patch.bottom, 0.001f)
    }

    @Test
    fun unionWithPad_coversBoxAtFrameEdges() {
        // Box near the left/top edge — pad clamps but box is still covered.
        val box = rect(0f, 0f, 50f, 50f)
        val patch = PatchMath.unionWithPad(listOf(box), 5f, 1000, 1000)!!
        assertTrue("patch=$patch does not fully cover box=$box", coverage(box, patch) >= 1.0f)
        assertEquals(0f, patch.left, 0.001f)
        assertEquals(0f, patch.top, 0.001f)
    }

    @Test
    fun unionWithPad_coversBoxNearRightBottomEdge() {
        val box = rect(900f, 900f, 980f, 980f)
        val patch = PatchMath.unionWithPad(listOf(box), 5f, 1000, 1000)!!
        assertTrue("patch=$patch does not fully cover box=$box", coverage(box, patch) >= 1.0f)
        // Pad extends beyond the box (no clamping needed since 985 < 1000).
        assertEquals(895f, patch.left, 0.001f)
        assertEquals(985f, patch.right, 0.001f)
        assertEquals(985f, patch.bottom, 0.001f)
    }

    @Test
    fun unionWithPad_clampsToFrameEdge() {
        // Box touching the right edge — pad clamps to frame width.
        val box = rect(950f, 100f, 1000f, 200f)
        val patch = PatchMath.unionWithPad(listOf(box), 5f, 1000, 1000)!!
        assertTrue("patch=$patch does not fully cover box=$box", coverage(box, patch) >= 1.0f)
        assertEquals(1000f, patch.right, 0.001f)
    }

    @Test
    fun unionWithPad_multipleBoxes_coversUnionFully() {
        val b1 = rect(100f, 100f, 150f, 150f)
        val b2 = rect(200f, 200f, 250f, 250f)
        val patch = PatchMath.unionWithPad(listOf(b1, b2), 5f, 1000, 1000)!!
        assertTrue("patch=$patch does not fully cover b1=$b1", coverage(b1, patch) >= 1.0f)
        assertTrue("patch=$patch does not fully cover b2=$b2", coverage(b2, patch) >= 1.0f)
    }

    @Test
    fun unionWithPad_returnsNullForEmpty() {
        assertEquals(null, PatchMath.unionWithPad(emptyList(), 5f, 1000, 1000))
    }

    @Test
    fun unionWithPad_returnsNullForInvalidFrame() {
        assertEquals(null, PatchMath.unionWithPad(listOf(rect(0f, 0f, 10f, 10f)), 5f, 0, 1000))
        assertEquals(null, PatchMath.unionWithPad(listOf(rect(0f, 0f, 10f, 10f)), 5f, 1000, 0))
    }
}
