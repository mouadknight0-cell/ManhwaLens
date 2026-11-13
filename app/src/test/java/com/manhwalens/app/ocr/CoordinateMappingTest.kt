package com.manhwalens.app.ocr

import com.manhwalens.app.render.computeContentRect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coordinate mapping after downscaling:
 *  - [BitmapScaler] target size + inverse scale round-trip (OCR space → capture space)
 *  - [computeContentRect] bitmap → view mapping (capture space → screen pixels)
 */
class CoordinateMappingTest {

    @Test
    fun targetSize_neverUpscales_andCapsLongestEdge() {
        assertEquals(1080 to 2400, BitmapScaler.computeTargetSize(1080, 2400, 3000))
        assertEquals(720 to 1600, BitmapScaler.computeTargetSize(1080, 2400, 1600))
        assertEquals(1600 to 900, BitmapScaler.computeTargetSize(2400, 1350, 1600))
        // degenerate input does not crash
        assertEquals(0 to 0, BitmapScaler.computeTargetSize(0, 0))
    }

    @Test
    fun ocrBoxes_mapBackToOriginalSpace_withinOnePixel() {
        val srcW = 1080
        val srcH = 2400
        val (scaledW, scaledH) = BitmapScaler.computeTargetSize(srcW, srcH, 1600)
        val inv = BitmapScaler.inverseScale(srcW, srcH, scaledW, scaledH)

        // A line detected at [36,120]–[360,180] in the SCALED image
        val back = floatArrayOf(36f * inv, 120f * inv, 360f * inv, 180f * inv)
        // long edge ratio must match in both axes (uniform scale)
        val invY = srcH.toFloat() / scaledH
        assertEquals(inv, invY, 0.02f)
        // boxes land in original space
        assertTrue(back[2] in 1f..srcW.toFloat())
        assertTrue(back[3] in 1f..srcH.toFloat())
        assertEquals(54f, back[0], 1.5f)
        assertEquals(180f, back[1], 1.5f)
    }

    @Test
    fun inverseScale_ofWithinBoundsTarget_isIdentity() {
        // frame already ≤ 1600 on the long edge → no downscale, inverse is 1
        val (w, h) = BitmapScaler.computeTargetSize(800, 1200, 1600)
        assertEquals(800 to 1200, w to h)
        assertEquals(1f, BitmapScaler.inverseScale(800, 1200, w, h), 1e-6f)
    }

    @Test
    fun inverseScale_ofCaptureSizedFrame_matchesLongEdgeCap() {
        // A 1080x2400 capture fed to the 1600-long-edge OCR scaler → 720x1600
        val (w, h) = BitmapScaler.computeTargetSize(1080, 2400, 1600)
        assertEquals(720 to 1600, w to h)
        assertEquals(1080f / 720f, BitmapScaler.inverseScale(1080, 2400, w, h), 1e-4f)
    }

    @Test
    fun contentRect_fit_mapsBoxWithCentering() {
        // View 1080x1920, bitmap 1080x2400 → scale 0.8, centered horizontally
        val rect = computeContentRect(1080f, 1920f, 1080, 2400)
        assertEquals(0.8f, rect.scale, 1e-6f)
        assertEquals(864f, rect.contentWidth, 0.5f)
        assertEquals(1920f, rect.contentHeight, 0.5f)
        assertEquals(108f, rect.offsetX, 0.5f)
        assertEquals(0f, rect.offsetY, 0.5f)

        val mapped = rect.mapBox(0, 0, 100, 100)
        assertEquals(108f, mapped[0], 0.5f)
        assertEquals(188f, mapped[2], 0.5f)   // 108 + 100*0.8
        assertEquals(80f, mapped[3] - mapped[1], 0.5f)
    }

    @Test
    fun contentRect_zeroSize_returnsZeroRect() {
        assertEquals(0f, computeContentRect(0f, 100f, 100, 100).scale, 1e-6f)
        assertEquals(0f, computeContentRect(100f, 100f, 0, 100).scale, 1e-6f)
        assertEquals(0f, computeContentRect(100f, 100f, 100, 0).scale, 1e-6f)
    }

    @Test
    fun contentRect_pillarboxInCenteredView() {
        // Small bitmap inside a large view → Fit centers it with margins both axes
        val rect = computeContentRect(1000f, 1000f, 200, 100)
        assertEquals(5f, rect.scale, 1e-6f)
        assertEquals(0f, rect.offsetX, 0.5f)     // width-limited: no horizontal margin
        assertEquals(250f, rect.offsetY, 0.5f)   // (1000-500)/2
        assertEquals(500f, rect.contentHeight, 0.5f)
    }
}
