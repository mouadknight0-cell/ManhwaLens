package com.manhwalens.app.live

import com.manhwalens.app.ocr.geometry.Rect2D
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnUiGuardsTest {
    private val menu = listOf(Rect2D(100f, 600f, 560f, 720f))

    @Test fun `menu label inside the menu rect is own ui`() {
        assertTrue(OwnUiFilter.isOwnUi(Rect2D(250f, 690f, 320f, 710f), menu))
    }

    @Test fun `bubble far from the menu is page text`() {
        assertFalse(OwnUiFilter.isOwnUi(Rect2D(150f, 200f, 450f, 330f), menu))
    }

    @Test fun `bubble merely touching the menu edge is page text`() {
        assertFalse(OwnUiFilter.isOwnUi(Rect2D(150f, 400f, 450f, 610f), menu))
    }

    @Test fun `block mostly covered by the menu is own ui`() {
        assertTrue(OwnUiFilter.isOwnUi(Rect2D(500f, 640f, 600f, 700f), menu))
    }

    @Test fun `no own rects keeps everything`() {
        assertFalse(OwnUiFilter.isOwnUi(Rect2D(0f, 0f, 10f, 10f), emptyList()))
    }

    @Test fun `tap patches clear only above the change threshold`() {
        assertFalse(TapPatchWatch.shouldClear(0f, 10f))
        assertFalse(TapPatchWatch.shouldClear(9.9f, 10f))
        assertTrue(TapPatchWatch.shouldClear(10.1f, 10f))
        assertFalse(TapPatchWatch.shouldClear(Float.NaN, 10f))
    }
}
