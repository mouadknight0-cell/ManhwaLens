package com.manhwalens.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.manhwalens.app.data.SettingsPrefs
import com.manhwalens.app.ocr.geometry.Rect2D
import com.manhwalens.app.util.CaptureDiagnostics
import com.manhwalens.app.util.OwnedWindows
import com.manhwalens.app.util.PipelineTracer
import com.manhwalens.app.util.StageTrace

/**
 * Runs the tap-capture OCR stages without depending on the foreground service:
 * crop, recognize, restore frame coordinates, group, filter, and record diagnostics.
 *
 * Platform lifecycle and user-facing error handling remain with the service.
 */
internal class CaptureOcrProcessor(
    context: Context,
    private val recognizer: TextRecognizerClient,
    private val cropForOcr: (Bitmap) -> Pair<Bitmap, Float>,
    private val ownedRects: () -> List<Rect2D>,
    private val recordOcrResult: (Int, Long) -> Unit,
    private val recordDrop: (String) -> Unit
) {
    private val appContext = context.applicationContext

    fun recognizeAndFilterBlocks(
        bitmap: Bitmap,
        ownedRectsOverride: List<Rect2D>? = null,
        diagnostics: CaptureDiagnostics? = null
    ): List<BubbleBlock> {
        val (ocrBitmap, cropOffsetY) = cropForOcr(bitmap)
        val canCrop = ocrBitmap !== bitmap
        // v0.13.0: T2 — image preprocessing (crop for OCR) complete.
        com.manhwalens.app.util.EndToEndLatency.mark(
            com.manhwalens.app.util.EndToEndLatency.Event.T2_PREPROCESS_COMPLETE
        )
        return try {
            val recognized = recognizer.recognize(ocrBitmap)
            // v0.13.0: T3 — OCR complete (recognized text back from the engine).
            com.manhwalens.app.util.EndToEndLatency.mark(
                com.manhwalens.app.util.EndToEndLatency.Event.T3_OCR_COMPLETE
            )
            val lines = if (canCrop) {
                recognized.lines.map { line ->
                    line.copy(
                        box = Rect2D(
                            line.box.left,
                            line.box.top + cropOffsetY,
                            line.box.right,
                            line.box.bottom + cropOffsetY
                        )
                    )
                }
            } else {
                recognized.lines
            }

            recordOcrResult(lines.size, recognized.elapsedMs)
            val grouped = BubbleGrouper.group(lines)
            val edgePrefs = SettingsPrefs(appContext)
            val density = appContext.resources.displayMetrics.density
            // v0.13.0: the FILTER trace records live HERE, next to the only filter pass.
            // (The service used to re-run BlockFilter + the garbage classifier on the
            // already-filtered blocks with identical parameters — a pure duplicate.)
            val fStart = SystemClock.elapsedRealtime()
            val filterResult = BlockFilter.apply(
                grouped,
                bitmap.height,
                edgePrefs.ignoreTopPct,
                edgePrefs.ignoreBottomPct,
                edgePrefs.ignorePhraseList(),
                minHeightPx = 16f * density,
                minWordCount = 1,
                frameWidth = bitmap.width
            )
            if (filterResult.edgeWarnings.isNotEmpty()) {
                PipelineTracer.span(
                    "horizontal edge OCR",
                    0,
                    filterResult.edgeWarnings.joinToString { "#${it.id}:${it.side}" }.take(240)
                )
            }
            val removed = filterResult.removed.joinToString(", ") { "#${it.id}:${it.reason}" }.take(300)
            PipelineTracer.record(
                PipelineTracer.Stage.FILTER, StageTrace.Status.OK,
                SystemClock.elapsedRealtime() - fStart,
                "kept ${filterResult.kept.size}/${grouped.size}" +
                    if (removed.isNotEmpty()) " · removed: $removed" else " · removed: none"
            )
            filterResult.removed.forEach { recordDrop(it.reason) }

            val owned = ownedRectsOverride ?: ownedRects()
            val filtered = if (filterResult.kept.isEmpty()) {
                emptyList()
            } else {
                val gStart = SystemClock.elapsedRealtime()
                val classifier = OcrGarbageClassifier(OwnedWindows.UI_STRINGS) {
                    SystemClock.elapsedRealtime()
                }
                val noPatchRects = emptyList<Rect2D>()
                val before = filterResult.kept.size
                val kept = filterResult.kept.filterNot { block ->
                    val verdict = classifier.classify(
                        block.text.replace('\n', ' ').trim(),
                        block.bounds,
                        patchRects = noPatchRects,
                        ownedRects = owned
                    )
                    if (!verdict.keep) {
                        recordDrop(verdict.reason.name)
                        PipelineTracer.span("ocr drop", 0, "#${block.id}=${verdict.reason}")
                    }
                    !verdict.keep
                }
                val dropped = before - kept.size
                PipelineTracer.record(
                    PipelineTracer.Stage.FILTER, StageTrace.Status.OK,
                    SystemClock.elapsedRealtime() - gStart,
                    "kept ${kept.size}/$before (garbage+own-window drops: $dropped)"
                )
                kept
            }
            // v0.13.0: T4 — grouping + filtering (sentence reconstruction input) complete.
            com.manhwalens.app.util.EndToEndLatency.mark(
                com.manhwalens.app.util.EndToEndLatency.Event.T4_GROUPING_COMPLETE
            )
            diagnostics?.recordOcr(lines, grouped, filtered, recognized.elapsedMs)
            filtered
        } finally {
            if (canCrop) runCatching { ocrBitmap.recycle() }
        }
    }
}
