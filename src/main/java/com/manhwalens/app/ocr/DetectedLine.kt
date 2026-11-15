package com.manhwalens.app.ocr

import com.manhwalens.app.ocr.geometry.Rect2D

/**
 * One OCR-detected text line, independent of the OCR engine.
 * Created by TextRecognizerClient from ML Kit; the grouper consumes these.
 */
data class DetectedLine(
    val text: String,
    val box: Rect2D,
    val confidence: Float = 0f
)
