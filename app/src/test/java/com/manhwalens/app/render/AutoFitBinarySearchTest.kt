package com.manhwalens.app.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.13.0 — the binary-search auto-fit must return EXACTLY what the old 1 px linear
 * descent returned, with a bounded number of measures.
 */
class AutoFitBinarySearchTest {

    /** Reference: the pre-v0.13.0 linear descent. */
    private fun linearFit(
        text: String,
        maxWidthPx: Int,
        maxHeightPx: Int,
        minPx: Float,
        maxPx: Float,
        measurer: TextMeasurer
    ): AutoFitResult {
        if (text.isBlank() || maxWidthPx <= 0 || maxHeightPx <= 0 || maxPx <= minPx) {
            return AutoFitResult(minPx.coerceAtLeast(1f), 0, text.isBlank())
        }
        var size = maxPx
        var lastHeight = 0
        while (size >= minPx) {
            val m = measurer.measure(text, maxWidthPx, size)
            lastHeight = m.heightPx
            if (m.heightPx <= maxHeightPx) return AutoFitResult(size, m.heightPx, true)
            size -= AutoFitTextSizer.STEP_PX
        }
        return AutoFitResult(minPx, lastHeight, false)
    }

    private class CountingMeasurer(private val delegate: TextMeasurer) : TextMeasurer {
        var calls = 0
        override fun measure(text: String, maxWidthPx: Int, fontSizePx: Float): Measured {
            calls++
            return delegate.measure(text, maxWidthPx, fontSizePx)
        }
    }

    private val cases = listOf(
        Triple("مرحبا بالعالم", 900, 400),
        Triple("هذه جملة عربية طويلة نسبيًا لاختبار الملاءمة داخل فقاعة صغيرة جدًا مع نصوص كثيرة", 180, 120),
        Triple("كلمة ".repeat(400).trim(), 120, 40), // unfittable -> floor
        Triple("a", 50, 30),
        Triple("Hello world this is a longer English sentence for wrapping", 220, 90),
        Triple("نص", 200, 60)
    )

    @Test
    fun binarySearchMatchesLinearDescentExactly() {
        val measurer = FakeMonospaceMeasurer()
        for ((text, w, h) in cases) {
            val expected = linearFit(text, w, h, 6f, 64f, measurer)
            val actual = AutoFitTextSizer.fit(text, w, h, 6f, 64f, measurer)
            assertEquals("fontSize for w=$w h=$h", expected.fontSizePx, actual.fontSizePx, 0.0001f)
            assertEquals("height for w=$w h=$h", expected.heightPx, actual.heightPx)
            assertEquals("fits for w=$w h=$h", expected.fits, actual.fits)
        }
    }

    @Test
    fun binarySearchUsesBoundedMeasures() {
        val counting = CountingMeasurer(FakeMonospaceMeasurer())
        val text = "هذه جملة عربية طويلة نسبيًا لاختبار الملاءمة داخل فقاعة صغيرة جدًا مع نصوص كثيرة"
        val result = AutoFitTextSizer.fit(text, 180, 120, 6f, 64f, counting)
        assertTrue(result.fits)
        // 59 grid sizes -> at most 6 binary-search probes (linear worst case: 59).
        assertTrue("expected <= 7 measures, got ${counting.calls}", counting.calls <= 7)
    }

    @Test
    fun maxSizeFittingImmediatelyStillWorks() {
        val counting = CountingMeasurer(FakeMonospaceMeasurer())
        val result = AutoFitTextSizer.fit("مرحبا", 900, 400, 6f, 64f, counting)
        assertEquals(64f, result.fontSizePx, 0.0001f)
        assertTrue(result.fits)
        assertTrue("expected <= 7 measures, got ${counting.calls}", counting.calls <= 7)
    }
}
