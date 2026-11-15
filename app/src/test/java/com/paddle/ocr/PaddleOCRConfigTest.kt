package com.paddle.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class PaddleOCRConfigTest {

    @Test
    fun defaultRecognitionThresholdRejectsLowConfidenceLines() {
        assertEquals(0.5f, PaddleOCRConfig().recScoreThresh, 0.0001f)
    }

    @Test
    fun recognitionThresholdCanBeOverriddenForCalibration() {
        assertEquals(0.35f, PaddleOCRConfig(recScoreThresh = 0.35f).recScoreThresh, 0.0001f)
    }
}
