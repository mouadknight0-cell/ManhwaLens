package com.manhwalens.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.manhwalens.app.data.SettingsPrefs
import com.manhwalens.app.ocr.geometry.Rect2D
import com.manhwalens.app.util.CaptureDiagnostics
import com.manhwalens.app.util.OwnedWindows
import com.manhwalens.app.util.PipelineTracer

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
        return try {
            val recognized = recognizer.recognize(ocrBitmap)
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
            val filterStartedAt = SystemClock.elapsedRealtime()
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
            filterResult.removed.forEach {
                recordDrop(it.reason)
                PipelineTracer.span("ocr drop", 0, "#${it.id}=${it.reason}")
            }

            val owned = ownedRectsOverride ?: ownedRects()
            var garbageDropped = 0
            var ownUiDropped = 0
            val filtered = if (filterResult.kept.isEmpty()) {
                emptyList()
            } else {
                val classifier = OcrGarbageClassifier(OwnedWindows.UI_STRINGS) {
                    SystemClock.elapsedRealtime()
                }
                val noPatchRects = emptyList<Rect2D>()
                val noOwnedRects = emptyList<Rect2D>()
                filterResult.kept.filterNot { block ->
                    val verdict = classifier.classify(
                        block.text.replace('\n', ' ').trim(),
                        block.bounds,
                        patchRects = noPatchRects,
                        ownedRects = noOwnedRects
                    )
                    val isOwnUi = com.manhwalens.app.live.OwnUiFilter.isOwnUi(block.bounds, owned)
                    if (!verdict.keep) {
                        garbageDropped++
                        recordDrop(verdict.reason.name)
                        PipelineTracer.span("ocr drop", 0, "#${block.id}=${verdict.reason}")
                    } else if (isOwnUi) {
                        ownUiDropped++
                        recordDrop("own-window")
                        PipelineTracer.span("ocr drop", 0, "#${block.id}=own-window")
                    }
                    !verdict.keep || isOwnUi
                }
            }
            PipelineTracer.record(
                PipelineTracer.Stage.FILTER,
                com.manhwalens.app.util.StageTrace.Status.OK,
                SystemClock.elapsedRealtime() - filterStartedAt,
                "kept=${filtered.size}/${grouped.size} blockFilter=${filterResult.removed.size} " +
                    "garbage=$garbageDropped ownUi=$ownUiDropped"
            )
            diagnostics?.recordOcr(lines, grouped, filtered, recognized.elapsedMs, recognized.engineStats)
            filtered
        } finally {
            if (canCrop) runCatching { ocrBitmap.recycle() }
        }
    }
}
