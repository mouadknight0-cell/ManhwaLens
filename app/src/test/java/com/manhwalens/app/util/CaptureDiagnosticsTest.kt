package com.manhwalens.app.util

import com.manhwalens.app.ocr.BubbleBlock
import com.manhwalens.app.ocr.DetectedLine
import com.manhwalens.app.ocr.geometry.Rect2D
import com.manhwalens.app.translate.mlkit.MlKitTranslationTrace
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureDiagnosticsTest {
    @Test
    fun reportConnectsRawOcrConfidenceCoordinatesAndBubbleText() {
        val line = DetectedLine("WHY ARE YOU HERE?", Rect2D(10f, 20f, 180f, 52f), 0.93f)
        val block = BubbleBlock(1, listOf(line))
        val diagnostics = CaptureDiagnostics(720, 1600, 1_800_000_000_000L)
        diagnostics.recordOcr(listOf(line), listOf(block), listOf(block), 42L)
        val report = diagnostics.report()
        assertTrue(report.contains("confidence=0.930"))
        assertTrue(report.contains("box=10.0,20.0,180.0,52.0"))
        assertTrue(report.contains("WHY ARE YOU HERE?"))
        assertTrue(report.contains("ocrMs=42"))
    }

    @Test
    fun reportIncludesPaddleEngineTimingsInputShapesAndConfidenceDrops() {
        val line = DetectedLine("A TEXT LINE", Rect2D(10f, 20f, 180f, 52f), 0.91f)
        val block = BubbleBlock(1, listOf(line))
        val diagnostics = CaptureDiagnostics(720, 1600)
        diagnostics.recordOcr(
            lines = listOf(line),
            grouped = listOf(block),
            kept = listOf(block),
            elapsedMs = 420L,
            engineStats = OcrRunStats(
                engine = "PaddleOCR",
                detectionTimeMs = 110L,
                recognitionTimeMs = 180L,
                totalTimeMs = 310L,
                detectionPreprocessMs = 20L,
                detectionInferenceMs = 70L,
                detectionPostprocessMs = 20L,
                recognitionPreprocessMs = 30L,
                recognitionInferenceMs = 130L,
                recognitionPostprocessMs = 20L,
                pipelineOverheadMs = 10L,
                coldLoadTimeMs = 900L,
                detectionInputShape = listOf(1, 3, 1920, 864),
                recognitionInputShapes = listOf(listOf(1, 3, 48, 320)),
                recognizedCandidateCount = 9,
                lowConfidenceDroppedCount = 2,
                confidenceThreshold = 0.5f
            )
        )

        val report = diagnostics.report()

        assertTrue(report.contains("[OCR ENGINE STATS] present=true"))
        assertTrue(report.contains("engine=PaddleOCR detectionMs=110 recognitionMs=180 totalEngineMs=310 wrapperMs=420"))
        assertTrue(report.contains("candidates=9 lowConfidenceDropped=2 recThreshold=0.500"))
        assertTrue(report.contains("input=1x3x1920x864"))
        assertTrue(report.contains("recognitionInputShapes=1x3x48x320"))
        assertTrue(report.contains("coldLoadMs=900"))
    }

    @Test
    fun reportIncludesExactPreparedInputRawOutputAndOverlayText() {
        val diagnostics = CaptureDiagnostics(720, 1600)
        diagnostics.recordTranslation(MlKitTranslationTrace(
            blockId = 7,
            sourceText = "Sunwoo, remember her?",
            preparedInput = "سونو، تذكرها؟",
            rawOutput = "سونو، تذكّرها؟",
            durationMs = 318L,
            operation = "parallel",
            error = null
        ))
        diagnostics.recordOverlayText(7, "Sunwoo, remember her?", "سونو، تذكّرها؟", "ml_kit_en_ar", true)
        val report = diagnostics.report()
        assertTrue(report.contains("block=#7"))
        assertTrue(report.contains("input =\"سونو، تذكرها؟\""))
        assertTrue(report.contains("raw   =\"سونو، تذكّرها؟\""))
        assertTrue(report.contains("patchBuilt=true"))
    }

    @Test
    fun reportIncludesActualPatchBoundsAndPlannedCoverMembers() {
        val diagnostics = CaptureDiagnostics(720, 1600)
        diagnostics.recordOverlayPatch(
            id = 4,
            patchLeft = 90, patchTop = 96, patchRight = 260, patchBottom = 164,
            cover = Rect2D(96f, 102f, 254f, 158f),
            memberIds = listOf(4, 5, 6)
        )

        val report = diagnostics.report()

        assertTrue(report.contains("[OVERLAY PATCHES] unique=1"))
        assertTrue(report.contains("#4 patch=90.0,96.0,260.0,164.0 cover=96.0,102.0,254.0,158.0 members=4,5,6"))
    }

    @Test
    fun reportIsBoundedWhenManyLongTranslationCallsAreRecorded() {
        val diagnostics = CaptureDiagnostics(720, 1600)
        repeat(200) { index ->
            diagnostics.recordTranslation(MlKitTranslationTrace(
                blockId = index,
                sourceText = "S".repeat(2_000),
                preparedInput = "I".repeat(2_000),
                rawOutput = "R".repeat(2_000),
                durationMs = 1L,
                operation = "parallel",
                error = null
            ))
        }
        diagnostics.recordOverlayText(999, "DISPLAY_SENTINEL", "النص المعروض", "ml_kit_en_ar", true)
        val report = diagnostics.report()
        assertTrue(report.length <= CaptureDiagnostics.MAX_REPORT_CHARS)
        assertFalse(report.isEmpty())
        assertTrue(report.contains("[ML KIT RAW CALLS]"))
        assertTrue(report.contains("[OVERLAY TEXT]"))
        assertTrue(report.contains("DISPLAY_SENTINEL"))
    }
}
