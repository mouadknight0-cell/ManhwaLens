package com.manhwalens.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BitmapScalerTest {

    @Test
    fun landscapeFrameDownscalesToMaxLongEdge() {
        val (w, h) = BitmapScaler.computeTargetSize(4000, 2000, 1600)
        assertEquals(1600 to 800, w to h)
    }

    @Test
    fun portraitFrameDownscalesToMaxLongEdge() {
        val (w, h) = BitmapScaler.computeTargetSize(1080, 4000, 1600)
        // 4000 -> 1600 keeps aspect: 1080 * 0.4 = 432
        assertEquals(432 to 1600, w to h)
    }

    @Test
    fun smallFrameIsNotUpscaled() {
        assertEquals(1200 to 800, BitmapScaler.computeTargetSize(1200, 800))
        assertEquals(1600 to 1600, BitmapScaler.computeTargetSize(1600, 1600, 1600))
    }

    @Test
    fun degenerateInputsClamped() {
        val (w, h) = BitmapScaler.computeTargetSize(0, 100)
        assertEquals(0 to 100, w to h)
        assertEquals(1 to 1, BitmapScaler.computeTargetSize(1, 1, 0))
    }

    @Test
    fun inverseScaleMapsBackToOriginalSpace() {
        val inv = BitmapScaler.inverseScale(4000, 2000, 1600, 800)
        assertEquals(2.5f, inv, 1e-6f)
        assertEquals(400f, 160f * inv, 1e-3f)
    }
}
