package com.paddle.ocr.preprocess

import org.junit.Assert.assertEquals
import org.junit.Test

class RecResizePolicyTest {

    @Test
    fun preservesAspectRatioAndUsesTheFortyEightPixelModelHeight() {
        assertEquals(160, RecResizePolicy.contentWidth(320, 96))
        assertEquals(320, RecResizePolicy.contentWidth(640, 96))
        assertEquals(640, RecResizePolicy.contentWidth(1280, 96))
    }

    @Test
    fun batchIsPaddedToAtLeastTheModels320PixelBaseWidth() {
        assertEquals(320, RecResizePolicy.batchWidth(listOf(72, 120, 196)))
        assertEquals(480, RecResizePolicy.batchWidth(listOf(72, 480, 196)))
    }

    @Test
    fun veryWideCropsAreBoundedToTheConfiguredMaximum() {
        assertEquals(3200, RecResizePolicy.contentWidth(8000, 48))
        assertEquals(3200, RecResizePolicy.batchWidth(listOf(8000), baseWidth = 320, maxWidth = 3200))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnEmptyRecognitionBatch() {
        RecResizePolicy.batchWidth(emptyList())
    }
}
