package com.manhwalens.app.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoFitTextSizerTest {

    private val measurer = FakeMonospaceMeasurer()

    @Test
    fun shortTextInBigBoxGetsBigFont() {
        val result = AutoFitTextSizer.fit(
            text = "مرحبا",
            maxWidthPx = 900,
            maxHeightPx = 400,
            minPx = 6f,
            maxPx = 64f,
            measurer = measurer
        )
        assertTrue("expected big font, got ${result.fontSizePx}", result.fontSizePx >= 60f)
        assertTrue(result.fits)
    }

    @Test
    fun longTextInSmallBoxGetsSmallFont() {
        val lines = "هذه جملة عربية طويلة نسبيًا لاختبار الملاءمة داخل فقاعة صغيرة جدًا مع نصوص كثيرة"
        val result = AutoFitTextSizer.fit(
            text = lines,
            maxWidthPx = 180,
            maxHeightPx = 120,
            minPx = 6f,
            maxPx = 64f,
            measurer = measurer
        )
        assertTrue("expected small font, got ${result.fontSizePx}", result.fontSizePx < 20f)
        assertTrue("should fit at the chosen size", result.fits)
    }

    @Test
    fun unfittableTextHitsFloorAndReportsFalse() {
        val huge = "كلمة ".repeat(400).trim()
        val result = AutoFitTextSizer.fit(
            text = huge,
            maxWidthPx = 120,
            maxHeightPx = 40,
            minPx = 6f,
            maxPx = 64f,
            measurer = measurer
        )
        assertEquals(6f, result.fontSizePx, 0.001f)
        assertFalse(result.fits)
    }

    @Test
    fun blankTextIsTrivialFit() {
        val result = AutoFitTextSizer.fit(
            text = "   ",
            maxWidthPx = 200,
            maxHeightPx = 60,
            measurer = measurer
        )
        assertTrue(result.fits)
    }

    @Test
    fun zeroBoxDegradesGracefully() {
        val result = AutoFitTextSizer.fit(
            text = "نص",
            maxWidthPx = 0,
            maxHeightPx = 0,
            measurer = measurer
        )
        assertFalse(result.fits)
    }
}
