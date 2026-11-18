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

    @Test
    fun keepsSpacesAtArabicLatinBoundaries() {
        assertEquals("مرحبا abc", CTCDecoder.reverseArabicVisualOrder("abc ابحرم"))
        assertEquals("abc مرحبا", CTCDecoder.reverseArabicVisualOrder("ابحرم abc"))
    }

    @Test
    fun preservesArabicIndicAndPersianDigitOrder() {
        assertEquals("مرحبا ١٢٣", CTCDecoder.reverseArabicVisualOrder("١٢٣ ابحرم"))
        assertEquals("مرحبا ۱۲۳", CTCDecoder.reverseArabicVisualOrder("۱۲۳ ابحرم"))
    }

    @Test
    fun keepsArabicQuestionMarkAtSentenceEndAfterVisualReversal() {
        assertEquals("مرحبا؟", CTCDecoder.reverseArabicVisualOrder("؟ابحرم"))
    }

    @Test
    fun ctcCollapsesAdjacentRepeatsButKeepsRepeatsSeparatedByBlank() {
        val output = floatArrayOf(
            0.05f, 0.90f, 0.05f,
            0.05f, 0.85f, 0.10f,
            0.90f, 0.05f, 0.05f,
            0.10f, 0.10f, 0.80f,
            0.05f, 0.10f, 0.85f
        )

        val decoded = CTCDecoder.decode(
            output,
            longArrayOf(1, 5, 3),
            listOf("A", "B")
        ).single()

        assertEquals("AB", decoded.first)
        assertEquals(0.85f, decoded.second, 0.0001f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMismatchedCtcTensorShape() {
        CTCDecoder.decode(floatArrayOf(0.1f, 0.9f, 0.0f), longArrayOf(1, 1, 4), listOf("A", "B"))
    }
}
