package com.manhwalens.app.ocr

/**
 * Compact engine diagnostics copied from one OCR run for opt-in local capture reports.
 * These metrics are observational only; they never affect OCR acceptance or grouping.
 */
data class OcrRunStats(
    val engine: String,
    val detectionTimeMs: Long,
    val recognitionTimeMs: Long,
    val totalTimeMs: Long,
    val detectionPreprocessMs: Long,
    val detectionInferenceMs: Long,
    val detectionPostprocessMs: Long,
    val recognitionPreprocessMs: Long,
    val recognitionInferenceMs: Long,
    val recognitionPostprocessMs: Long,
    val pipelineOverheadMs: Long,
    val coldLoadTimeMs: Long,
    val detectionInputShape: List<Int>,
    val recognitionInputShapes: List<List<Int>>,
    val recognizedCandidateCount: Int,
    val lowConfidenceDroppedCount: Int,
    val confidenceThreshold: Float,
    val detectionThreshold: Float,
    val detectionBoxThreshold: Float,
    val detectionUnclipRatio: Float,
    val detectorLimitSideLen: Int,
    val detectorMaxSideLimit: Int,
    val detectorLimitType: String,
    val engineThreads: Int
)
