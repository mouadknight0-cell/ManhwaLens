package com.manhwalens.app.util

import com.manhwalens.app.ocr.BubbleBlock
import com.manhwalens.app.ocr.DetectedLine
import com.manhwalens.app.ocr.OcrRunStats
import com.manhwalens.app.ocr.geometry.Rect2D
import com.manhwalens.app.translate.mlkit.MlKitTranslationTrace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bounded in-memory diagnostics for one user-requested debug capture. */
class CaptureDiagnostics(
    private val frameWidth: Int,
    private val frameHeight: Int,
    private val createdAtMs: Long = System.currentTimeMillis()
) {
    private val lock = Any()
    private var ocrLines = emptyList<DetectedLine>()
    private var groupedBlocks = emptyList<BubbleBlock>()
    private var keptBlocks = emptyList<BubbleBlock>()
    private var ocrElapsedMs: Long = -1L
    private var ocrEngineStats: OcrRunStats? = null
    private var rawLineTotal = 0
    private var groupedTotal = 0
    private var keptTotal = 0
    private var translationCallTotal = 0
    private val translationCalls = ArrayList<MlKitTranslationTrace>()
    private val overlayTexts = LinkedHashMap<Int, OverlayText>()
    private val overlayPatches = LinkedHashMap<Int, OverlayPatch>()

    private data class OverlayText(
        val english: String,
        val arabic: String,
        val origin: String,
        val patchBuilt: Boolean
    )

    private data class OverlayPatch(
        val patch: Rect2D,
        val cover: Rect2D?,
        val memberIds: List<Int>
    )

    fun recordOcr(
        lines: List<DetectedLine>,
        grouped: List<BubbleBlock>,
        kept: List<BubbleBlock>,
        elapsedMs: Long,
        engineStats: OcrRunStats? = null
    ) {
        synchronized(lock) {
            rawLineTotal = lines.size
            groupedTotal = grouped.size
            keptTotal = kept.size
            ocrLines = lines.take(MAX_OCR_LINES)
            groupedBlocks = grouped.take(MAX_BLOCKS)
            keptBlocks = kept.take(MAX_BLOCKS)
            ocrElapsedMs = elapsedMs.coerceAtLeast(0L)
            ocrEngineStats = engineStats
        }
    }

    fun recordTranslation(trace: MlKitTranslationTrace) {
        synchronized(lock) {
            translationCallTotal++
            if (translationCalls.size >= MAX_TRANSLATION_CALLS) translationCalls.removeAt(0)
            translationCalls += trace.copy(
                sourceText = trace.sourceText.take(MAX_TEXT_CHARS),
                preparedInput = trace.preparedInput.take(MAX_TEXT_CHARS),
                rawOutput = trace.rawOutput.take(MAX_TEXT_CHARS)
            )
        }
    }

    /** Record after the renderer has successfully built a patch bitmap. */
    fun recordOverlayText(id: Int, english: String, arabic: String, origin: String, patchBuilt: Boolean) {
        synchronized(lock) {
            overlayTexts[id] = OverlayText(
                english.take(MAX_TEXT_CHARS), arabic.take(MAX_TEXT_CHARS), origin.take(40), patchBuilt
            )
            while (overlayTexts.size > MAX_BLOCKS) overlayTexts.remove(overlayTexts.keys.first())
        }
    }

    /** Record actual raster bounds and planned OCR cover; geometry only, no page text. */
    fun recordOverlayPatch(
        id: Int,
        patchLeft: Int,
        patchTop: Int,
        patchRight: Int,
        patchBottom: Int,
        cover: Rect2D?,
        memberIds: List<Int>
    ) {
        synchronized(lock) {
            overlayPatches[id] = OverlayPatch(
                Rect2D(patchLeft.toFloat(), patchTop.toFloat(), patchRight.toFloat(), patchBottom.toFloat()),
                cover,
                memberIds.take(MAX_BLOCKS)
            )
            while (overlayPatches.size > MAX_BLOCKS) overlayPatches.remove(overlayPatches.keys.first())
        }
    }

    fun report(): String = synchronized(lock) {
        val created = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(createdAtMs))
        val rawOcr = buildString {
            ocrLines.forEachIndexed { index, line ->
                val b = line.box
                appendLine("L${index + 1} confidence=${formatConfidence(line.confidence)} " +
                    "box=${format(b.left)},${format(b.top)},${format(b.right)},${format(b.bottom)} " +
                    "text=\"${safe(line.text)}\"")
            }
        }
        val engineStats = ocrEngineStats?.let { stats ->
            buildString {
                appendLine(
                    "engine=${stats.engine} detectionMs=${stats.detectionTimeMs} " +
                        "recognitionMs=${stats.recognitionTimeMs} totalEngineMs=${stats.totalTimeMs} " +
                        "wrapperMs=$ocrElapsedMs candidates=${stats.recognizedCandidateCount} " +
                        "lowConfidenceDropped=${stats.lowConfidenceDroppedCount} " +
                        "recThreshold=${formatConfidence(stats.confidenceThreshold)}"
                )
                appendLine(
                    "detectionStageMs=pre:${stats.detectionPreprocessMs},infer:${stats.detectionInferenceMs}," +
                        "post:${stats.detectionPostprocessMs} input=${stats.detectionInputShape.joinToString("x")}"
                )
                appendLine(
                    "recognitionStageMs=pre:${stats.recognitionPreprocessMs},infer:${stats.recognitionInferenceMs}," +
                        "post:${stats.recognitionPostprocessMs} pipelineOverheadMs=${stats.pipelineOverheadMs} " +
                        "coldLoadMs=${stats.coldLoadTimeMs}"
                )
                if (stats.recognitionInputShapes.isNotEmpty()) {
                    appendLine("recognitionInputShapes=${stats.recognitionInputShapes.take(4).joinToString(";") { it.joinToString("x") }}")
                }
            }
        }.orEmpty()
        val grouped = buildString {
            groupedBlocks.forEach { block ->
                appendLine("#${block.id} box=${box(block)} text=\"${safe(block.text)}\"")
            }
        }
        val kept = buildString {
            keptBlocks.forEach { block -> appendLine("#${block.id} text=\"${safe(block.text)}\"") }
        }
        val calls = buildString {
            translationCalls.forEachIndexed { index, call ->
                val error = call.error ?: "none"
                appendLine("CALL ${index + 1} block=#${call.blockId} operation=${call.operation} " +
                    "elapsedMs=${call.durationMs} error=$error")
                appendLine("  source=\"${safe(call.sourceText)}\"")
                appendLine("  input =\"${safe(call.preparedInput)}\"")
                appendLine("  raw   =\"${safe(call.rawOutput)}\"")
            }
        }
        val overlay = buildString {
            overlayTexts.forEach { (id, item) ->
                appendLine("#$id origin=${item.origin} patchBuilt=${item.patchBuilt} " +
                    "source=\"${safe(item.english)}\" arabic=\"${safe(item.arabic)}\"")
            }
        }
        val patches = buildString {
            overlayPatches.forEach { (id, item) ->
                val cover = item.cover?.let { box(it) } ?: "none"
                appendLine("#$id patch=${box(item.patch)} cover=$cover members=${item.memberIds.joinToString(",")}")
            }
        }
        buildString {
            appendLine("ManhwaLens OCR/translation diagnostics")
            appendLine("created=$created")
            appendLine("frame=${frameWidth}x${frameHeight} ocrMs=$ocrElapsedMs")
            appendLine("Privacy: opt-in debug-mode report; page text is not written to Logcat.")
            appendLine()
            append(boundedSection("[OCR ENGINE STATS] present=${ocrEngineStats != null}", engineStats, OCR_STATS_SECTION_CHARS))
            append(boundedSection("[RAW OCR LINES] total=$rawLineTotal captured=${ocrLines.size}", rawOcr, OCR_SECTION_CHARS))
            append(boundedSection("[GROUPED BLOCKS] total=$groupedTotal captured=${groupedBlocks.size}", grouped, BLOCK_SECTION_CHARS))
            append(boundedSection("[KEPT FOR TRANSLATION] total=$keptTotal captured=${keptBlocks.size}", kept, BLOCK_SECTION_CHARS))
            append(boundedSection("[ML KIT RAW CALLS] total=$translationCallTotal captured=${translationCalls.size}", calls, TRANSLATION_SECTION_CHARS))
            appendLine("[OVERLAY TEXT] unique=${overlayTexts.size}")
            appendLine("Entries are recorded only after a patch bitmap is built.")
            append(overlay.take(OVERLAY_SECTION_CHARS))
            if (overlay.length > OVERLAY_SECTION_CHARS) appendLine("\n... overlay section truncated")
            append(boundedSection("[OVERLAY PATCHES] unique=${overlayPatches.size}", patches, PATCH_SECTION_CHARS))
            appendLine("Patch bounds are the rasterized bitmap rect; cover bounds are the planned OCR union. Members are block IDs only.")
            appendLine()
            appendLine("Missing raw-call entries usually indicate a cache hit, another engine, or a request that failed before returning output.")
        }.take(MAX_REPORT_CHARS)
    }

    private fun boundedSection(title: String, content: String, maxChars: Int): String = buildString {
        appendLine(title)
        append(content.take(maxChars))
        if (content.length > maxChars) appendLine("\n... section truncated")
    }

    private fun box(block: BubbleBlock): String {
        val b = block.bounds
        return "${format(b.left)},${format(b.top)},${format(b.right)},${format(b.bottom)}"
    }

    private fun box(rect: Rect2D): String =
        "${format(rect.left)},${format(rect.top)},${format(rect.right)},${format(rect.bottom)}"


    private fun safe(value: String): String =
        value.replace("\r", " ").replace("\n", " ↵ ").take(MAX_TEXT_CHARS)

    private fun format(value: Float): String = String.format(Locale.US, "%.1f", value)

    private fun formatConfidence(value: Float): String = String.format(Locale.US, "%.3f", value)

    companion object {
        const val MAX_REPORT_CHARS = 56_000
        private const val MAX_TEXT_CHARS = 400
        private const val MAX_OCR_LINES = 120
        private const val MAX_BLOCKS = 80
        private const val MAX_TRANSLATION_CALLS = 120
        private const val OCR_STATS_SECTION_CHARS = 2_000
        private const val OCR_SECTION_CHARS = 10_000
        private const val BLOCK_SECTION_CHARS = 7_000
        private const val TRANSLATION_SECTION_CHARS = 20_000
        private const val OVERLAY_SECTION_CHARS = 8_000
        private const val PATCH_SECTION_CHARS = 4_000
    }
}
