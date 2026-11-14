package com.manhwalens.app.util

import com.manhwalens.app.ocr.BubbleBlock
import com.manhwalens.app.ocr.DetectedLine
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
    private val translationCalls = ArrayList<MlKitTranslationTrace>()
    private val overlayTexts = LinkedHashMap<Int, OverlayText>()

    private data class OverlayText(
        val english: String,
        val arabic: String,
        val origin: String,
        val patchBuilt: Boolean
    )

    fun recordOcr(lines: List<DetectedLine>, grouped: List<BubbleBlock>, kept: List<BubbleBlock>, elapsedMs: Long) {
        synchronized(lock) {
            ocrLines = lines.take(MAX_OCR_LINES)
            groupedBlocks = grouped.take(MAX_BLOCKS)
            keptBlocks = kept.take(MAX_BLOCKS)
            ocrElapsedMs = elapsedMs.coerceAtLeast(0L)
        }
    }

    fun recordTranslation(trace: MlKitTranslationTrace) {
        synchronized(lock) {
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

    fun report(): String = synchronized(lock) {
        buildString {
            appendLine("ManhwaLens OCR/translation diagnostics")
            appendLine("created=${SimpleDateFormat(\"yyyy-MM-dd HH:mm:ss\", Locale.US).format(Date(createdAtMs))}")
            appendLine("frame=${frameWidth}x${frameHeight} ocrMs=$ocrElapsedMs")
            appendLine("Privacy: opt-in debug-mode report; page text is not written to Logcat.")
            appendLine()
            appendLine("[RAW OCR LINES] count=${ocrLines.size}")
            ocrLines.forEachIndexed { index, line ->
                val b = line.box
                appendLine("L${index + 1} confidence=${format(line.confidence)} " +
                    "box=${format(b.left)},${format(b.top)},${format(b.right)},${format(b.bottom)} " +
                    "text=\"${safe(line.text)}\"")
            }
            appendLine()
            appendLine("[GROUPED BLOCKS] count=${groupedBlocks.size}")
            groupedBlocks.forEach { block ->
                appendLine("#${block.id} box=${box(block)} text=\"${safe(block.text)}\"")
            }
            appendLine()
            appendLine("[KEPT FOR TRANSLATION] count=${keptBlocks.size}")
            keptBlocks.forEach { block -> appendLine("#${block.id} text=\"${safe(block.text)}\"") }
            appendLine()
            appendLine("[ML KIT RAW CALLS] count=${translationCalls.size}")
            appendLine("Each entry includes original OCR source, exact prepared input, raw engine output and latency.")
            translationCalls.forEachIndexed { index, call ->
                appendLine("CALL ${index + 1} block=#${call.blockId} operation=${call.operation} " +
                    "elapsedMs=${call.durationMs} error=${call.error ?: \"none\"}")
                appendLine("  source=\"${safe(call.sourceText)}\"")
                appendLine("  input =\"${safe(call.preparedInput)}\"")
                appendLine("  raw   =\"${safe(call.rawOutput)}\"")
            }
            appendLine()
            appendLine("[OVERLAY TEXT] count=${overlayTexts.size}")
            appendLine("Entries are recorded only after a patch bitmap is built.")
            overlayTexts.forEach { (id, item) ->
                appendLine("#$id origin=${item.origin} patchBuilt=${item.patchBuilt} " +
                    "source=\"${safe(item.english)}\" arabic=\"${safe(item.arabic)}\"")
            }
            appendLine()
            appendLine("Missing raw-call entries usually indicate a cache hit, another engine, or a request that failed before returning output.")
        }.take(MAX_REPORT_CHARS)
    }

    private fun box(block: BubbleBlock): String {
        val b = block.bounds
        return "${format(b.left)},${format(b.top)},${format(b.right)},${format(b.bottom)}"
    }

    private fun safe(value: String): String =
        value.replace("\r", " ").replace("\n", " ↵ ").take(MAX_TEXT_CHARS)

    private fun format(value: Float): String = String.format(Locale.US, "%.1f", value)

    companion object {
        const val MAX_REPORT_CHARS = 24_000
        private const val MAX_TEXT_CHARS = 1_000
        private const val MAX_OCR_LINES = 120
        private const val MAX_BLOCKS = 80
        private const val MAX_TRANSLATION_CALLS = 120
    }
}
