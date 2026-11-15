package com.paddle.ocr.preprocess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetResizeCalculatorTest {

    @Test
    fun min1280PreservesMorePixelsOnTallPhoneScreensThanMax1280() {
        val current = DetResizeCalculator.calculate(
            originalWidth = 1080,
            originalHeight = 2400,
            limitSideLen = 1280,
            limitType = "max",
            maxSideLimit = 1920
        )
        val quality = DetResizeCalculator.calculate(
            originalWidth = 1080,
            originalHeight = 2400,
            limitSideLen = 1280,
            limitType = "min",
            maxSideLimit = 1920
        )

        assertEquals(576, current.width)
        assertEquals(1280, current.height)
        assertEquals(736, quality.width)
        assertEquals(1920, quality.height)
        assertTrue(quality.width * quality.height > current.width * current.height)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedResizePolicy() {
        DetResizeCalculator.calculate(1080, 2400, 1280, "unknown", 1920)
    }
}
