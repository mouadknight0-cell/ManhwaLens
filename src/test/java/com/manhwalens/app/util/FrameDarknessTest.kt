package com.manhwalens.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameDarknessTest {

    private fun gridFilledWith(color: Int) = IntArray(9) { color }

    @Test
    fun allBlack_isNearlyBlack() {
        assertTrue(FrameDarkness.isNearlyBlackSamples(gridFilledWith(0xFF000000.toInt())))
    }

    @Test
    fun allWhite_notNearlyBlack() {
        assertFalse(FrameDarkness.isNearlyBlackSamples(gridFilledWith(0xFFFFFFFF.toInt())))
    }

    @Test
    fun eightOfNineDark_isNearlyBlack() {
        val grid = gridFilledWith(0xFF0A0A0A.toInt())
        grid[4] = 0xFFFFFFFF.toInt() // only the center sample is bright
        assertTrue(FrameDarkness.isNearlyBlackSamples(grid))
    }

    @Test
    fun sevenOfNineDark_notNearlyBlack() {
        val grid = gridFilledWith(0xFF0A0A0A.toInt())
        grid[4] = 0xFFFFFFFF.toInt()
        grid[0] = 0xFFFFFFFF.toInt()
        assertFalse(FrameDarkness.isNearlyBlackSamples(grid))
    }

    @Test
    fun grayJustBelowThreshold_countsAsDark() {
        // luma 31 < 32 → dark
        assertTrue(FrameDarkness.isNearlyBlackSamples(gridFilledWith(0xFF1F1F1F.toInt())))
    }

    @Test
    fun grayAboveThreshold_notDark() {
        // luma 33 → not dark
        assertFalse(FrameDarkness.isNearlyBlackSamples(gridFilledWith(0xFF212121.toInt())))
    }

    @Test
    fun rawPixelSurvey_allBlackFrame() {
        val w = 64; val h = 64
        assertTrue(FrameDarkness.isNearlyBlackPixels(IntArray(w * h) { 0xFF000000.toInt() }, w, h))
    }

    @Test
    fun rawPixelSurvey_brightFrameNotDark() {
        val w = 64; val h = 64
        assertFalse(FrameDarkness.isNearlyBlackPixels(IntArray(w * h) { 0xFFEDEDED.toInt() }, w, h))
    }

    @Test
    fun rawPixelSurvey_badInputNeverCrashes() {
        assertFalse(FrameDarkness.isNearlyBlackPixels(IntArray(0), 0, 0))
        assertFalse(FrameDarkness.isNearlyBlackPixels(IntArray(10), 64, 64))
    }

    // ---------- v0.9.11: protected-frame check ----------

    private fun dense(color: Int) = IntArray(FrameDarkness.DENSE_GRID * FrameDarkness.DENSE_GRID) { color }

    @Test
    fun pureBlackFrame_isProtected() {
        assertTrue(FrameDarkness.isProtectedBlack(dense(0xFF000000.toInt())))
    }

    @Test
    fun darkThemePage_isNotProtected() {
        // #121212 background — the old 3x3 test called this black and skipped the pipeline.
        assertFalse(FrameDarkness.isProtectedBlack(dense(0xFF121212.toInt())))
        assertTrue(FrameDarkness.isNearlyBlackSamples(IntArray(9) { 0xFF121212.toInt() }))
    }

    @Test
    fun blackPageWithText_isNotProtected() {
        // AMOLED-black page where 2 % of the samples hit white text / a bubble.
        val s = dense(0xFF000000.toInt())
        for (i in 0 until s.size / 50) s[i * 50] = 0xFFFFFFFF.toInt()
        assertFalse(FrameDarkness.isProtectedBlack(s))
    }

    @Test
    fun protectedFrame_toleratesOurOwnLensButton() {
        // ≤ 0.3 % bright samples (the floating lens drawn over a protected app).
        val s = dense(0xFF000000.toInt())
        for (i in 0 until 4) s[i] = 0xFFFFC45E.toInt()
        assertTrue(FrameDarkness.isProtectedBlack(s))
        assertEquals(4, FrameDarkness.nonBlackCount(s))
    }
}
