package com.manhwalens.app.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentRectTest {

    @Test
    fun widerBitmapFitsToWidthAndCentersVertically() {
        val cr = computeContentRect(1000f, 800f, 4000, 2000)
        assertEquals(0.25f, cr.scale, 1e-6f)
        assertEquals(1000f, cr.contentWidth, 1e-3f)
        assertEquals(500f, cr.contentHeight, 1e-3f)
        assertEquals(0f, cr.offsetX, 1e-3f)
        assertEquals(150f, cr.offsetY, 1e-3f)   // (800-500)/2
    }

    @Test
    fun tallerBitmapFitsToHeightAndCentersHorizontally() {
        val cr = computeContentRect(1000f, 800f, 1000, 4000)
        assertEquals(0.2f, cr.scale, 1e-6f)
        assertEquals(200f, cr.contentWidth, 1e-3f)
        assertEquals(0f, cr.offsetY, 1e-3f)
        assertEquals(400f, cr.offsetX, 1e-3f)   // (1000-200)/2
    }

    @Test
    fun exactAspectFillsEverything() {
        val cr = computeContentRect(800f, 600f, 1600, 1200)
        assertEquals(0.5f, cr.scale, 1e-6f)
        assertEquals(0f, cr.offsetX, 1e-3f)
        assertEquals(0f, cr.offsetY, 1e-3f)
    }

    @Test
    fun zeroSizesReturnZeroRect() {
        assertEquals(0f, computeContentRect(0f, 0f, 100, 100).scale, 0f)
        assertEquals(0f, computeContentRect(100f, 100f, 0, 100).scale, 0f)
    }

    @Test
    fun mapBoxScalesAndOffsets() {
        val cr = computeContentRect(1000f, 800f, 4000, 2000) // scale .25, offsetY 150
        val mapped = cr.mapBox(400, 200, 800, 400)
        assertEquals(100f, mapped[0], 1e-3f)
        assertEquals(200f, mapped[1], 1e-3f)   // 200*.25 + 150
        assertEquals(200f, mapped[2], 1e-3f)
        assertEquals(250f, mapped[3], 1e-3f)   // 400*.25 + 150
    }
}
