package com.paddle.ocr.postprocess

import org.junit.Assert.assertEquals
import org.junit.Test

class CTCDecoderTest {
    @Test
    fun reversesArabicVisualCharacterOrder() {
        assertEquals("مرحبا", CTCDecoder.reverseArabicVisualOrder("ابحرم"))
    }

    @Test
    fun preservesLatinAndNumericRuns() {
        assertEquals("English 123", CTCDecoder.reverseArabicVisualOrder("English 123"))
    }
}
