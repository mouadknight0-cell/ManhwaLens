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
            appendLine("created=\u0024{SimpleDateFormat(\"yyyy-MM-dd HH:mm:ss\", Locale.US).format(Date(createdAtMs))}")
            appendLine("frame=\u0024{frameWidth}x\u0024{frameHeight} ocrMs=\u0024ocrElapsedMs")
            appendLine("Privacy: opt-in debug-mode report; page text is not written to Logcat.")
            appendLine()
            appendLine("[RAW OCR LINES] count=\u0024{ocrLines.size}")
            ocrLines.forEachIndexed { index, line ->
                val b = line.box
                appendLine("L\u0024{index + 1} confidence=\u0024{format(line.confidence)} " +
                    "box=\u0024{format(b.left)},\u0024{format(b.top)},\u0024{format(b.right)},\u0024{format(b.bottom)} " +
                    "text=\"\u0024{safe(line.text)}\"")
            }
            appendLine()
            appendLine("[GROUPED BLOCKS] count=\u0024{groupedBlocks.size}")
            groupedBlocks.forEach { block ->
                appendLine("#\u0024{block.id} box=\u0024{box(block)} text=\"\u0024{safe(block.text)}\"")
            }
            appendLine()
            appendLine("[KEPT FOR TRANSLATION] count=\u0024{keptBlocks.size}")
            keptBlocks.forEach { block -> appendLine("#\u0024{block.id} text=\"\u0024{safe(block.text)}\"") }
            appendLine()
            appendLine("[ML KIT RAW CALLS] count=\u0024{translationCalls.size}")
            appendLine("Each entry includes original OCR source, exact prepared input, raw engine output and latency.")
            translationCalls.forEachIndexed { index, call ->
                appendLine("CALL \u0024{index + 1} block=#\u0024{call.blockId} operation=\u0024{call.operation} " +
                    "elapsedMs=\u0024{call.durationMs} error=\u0024{call.error ?: \"none\"}")
                appendLine("  source=\"\u0024{safe(call.sourceText)}\"")
                appendLine("  input =\"\u0024{safe(call.preparedInput)}\"")
                appendLine("  raw   =\"\u0024{safe(call.rawOutput)}\"")
            }
            appendLine()
            appendLine("[OVERLAY TEXT] count=\u0024{overlayTexts.size}")
            appendLine("Entries are recorded only after a patch bitmap is built.")
            overlayTexts.forEach { (id, item) ->
                appendLine("#\u0024id origin=\u0024{item.origin} patchBuilt=\u0024{item.patchBuilt} " +
                    "source=\"\u0024{safe(item.english)}\" arabic=\"\u0024{safe(item.arabic)}\"")
            }
            appendLine()
            appendLine("Missing raw-call entries usually indicate a cache hit, another engine, or a request that failed before returning output.")
        }.take(MAX_REPORT_CHARS)
    }

    private fun box(block: BubbleBlock): String {
        val b = block.bounds
        return "\u0024{format(b.left)},\u0024{format(b.top)},\u0024{format(b.right)},\u0024{format(b.bottom)}"
    }

    private fun safe(value: String): String =
        value.replace("\\r", " ").replace("\\n", " ↵ ").take(MAX_TEXT_CHARS)

    private fun format(value: Float): String = String.format(Locale.US, "%.1f", value)

    companion object {
        const val MAX_REPORT_CHARS = 24_000
        private const val MAX_TEXT_CHARS = 1_000
        private const val MAX_OCR_LINES = 120
        private const val MAX_BLOCKS = 80
        private const val MAX_TRANSLATION_CALLS = 120
    }
}
