package com.manhwalens.app.translate.bergamot

import android.content.Context
import android.os.SystemClock
import com.manhwalens.app.translate.FailureReason
import com.manhwalens.app.translate.PageTranslation
import com.manhwalens.app.translate.TranslatedBlock
import com.manhwalens.app.translate.TranslationBlock
import com.manhwalens.app.translate.Translator
import com.manhwalens.app.util.PipelineTracer
import kotlinx.coroutines.runBlocking

/** Blocking adapter matching ManhwaLens' existing Translator contract. */
internal class BergamotPageTranslator(context: Context) : Translator {
    private val app = context.applicationContext
    private val engine = BergamotEnArTranslator(app)
    override val engineId: String = "bergamot_en_ar"

    /** Warm native service/model before the first reader capture. Run off the main thread. */
    fun warmUp() = engine.warmUp()

    /** Release native allocations; the next translation lazily reloads the model. */
    fun release() = engine.release()

    override fun translatePage(blocks: List<TranslationBlock>): PageTranslation {
        if (blocks.isEmpty()) return PageTranslation.Failure(
            FailureReason.EMPTY_INPUT, "No blocks to translate", engineId
        )
        if (!BergamotEnArModel.isInstalled(app)) return PageTranslation.Failure(
            FailureReason.MODEL_DOWNLOAD_FAILED,
            "Download the Bergamot English→Arabic model in Settings first",
            engineId
        )

        val startedNs = SystemClock.elapsedRealtimeNanos()
        try {
            // OCR line wrapping is visual layout, not sentence structure. Normalize each
            // bubble to one line while preserving the untouched OCR source for diagnostics.
            val prepStartedNs = SystemClock.elapsedRealtimeNanos()
            val engineInput = blocks.map { block -> block.english.asSingleLine() }

            // De-duplicate exact normalized bubbles within this page. Blank bubbles are
            // mapped directly to blank output and never consume native inference time.
            val uniqueIndexes = LinkedHashMap<String, Int>(engineInput.size)
            val uniqueTexts = ArrayList<String>(engineInput.size)
            val inputIndexes = engineInput.map { text ->
                if (text.isBlank()) {
                    -1
                } else {
                    uniqueIndexes[text] ?: run {
                        val index = uniqueTexts.size
                        uniqueIndexes[text] = index
                        uniqueTexts.add(text)
                        index
                    }
                }
            }
            val prepMs = (SystemClock.elapsedRealtimeNanos() - prepStartedNs) / 1_000_000L
            PipelineTracer.span(
                "Bergamot prepare",
                prepMs,
                "blocks=${blocks.size} unique=${uniqueTexts.size} duplicates=${blocks.size - uniqueTexts.size - inputIndexes.count { it == -1 }} chars=${uniqueTexts.sumOf { it.length }}"
            )

            val outputs = MutableList(uniqueTexts.size) { "" }
            val batches = adaptiveBatches(uniqueTexts)
            var nativeMs = 0L
            batches.forEachIndexed { batchIndex, batch ->
                val batchStartedNs = SystemClock.elapsedRealtimeNanos()
                val translated = runBlocking { engine.translate(batch) }
                check(translated.size == batch.size) {
                    "Bergamot returned ${translated.size} translations for ${batch.size} unique bubbles"
                }
                val elapsed = (SystemClock.elapsedRealtimeNanos() - batchStartedNs) / 1_000_000L
                nativeMs += elapsed
                val offset = batches.take(batchIndex).sumOf { it.size }
                translated.forEachIndexed { index, value -> outputs[offset + index] = value }
                PipelineTracer.span(
                    "Bergamot native batch",
                    elapsed,
                    "batch=${batchIndex + 1}/${batches.size} items=${batch.size} chars=${batch.sumOf { it.length }}"
                )
            }

            val resultBlocks = blocks.mapIndexed { index, source ->
                val outputIndex = inputIndexes[index]
                TranslatedBlock(source.id, source.english, if (outputIndex < 0) "" else outputs[outputIndex])
            }
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000L
            PipelineTracer.span(
                "Bergamot page total",
                elapsedMs,
                "blocks=${blocks.size} unique=${uniqueTexts.size} batches=${batches.size} native_ms=$nativeMs"
            )
            return PageTranslation.Success(
                blocks = resultBlocks,
                engineId = engineId,
                elapsedMs = elapsedMs,
                cacheHits = 0,
                cacheMisses = uniqueTexts.size
            )
        } catch (e: Exception) {
            val elapsedMs = (SystemClock.elapsedRealtimeNanos() - startedNs) / 1_000_000L
            PipelineTracer.span(
                "Bergamot failure",
                elapsedMs,
                "${e.javaClass.simpleName}: ${e.message.orEmpty()}".take(240)
            )
            return PageTranslation.Failure(FailureReason.UNKNOWN, e.message ?: e.javaClass.simpleName, engineId)
        }
    }

    /**
     * Keep ordinary pages in one JNI call. For unusually large pages, cap each batch
     * using both item count and character count to bound temporary UTF-8/native buffers.
     * Longer average bubbles use smaller batches; no parallel JNI calls are made because
     * the native service/model is intentionally serialized.
     */
    private fun adaptiveBatches(texts: List<String>): List<List<String>> {
        if (texts.isEmpty()) return emptyList()
        val averageLength = texts.sumOf { it.length }.toDouble() / texts.size
        val maxItems = when {
            averageLength <= 100.0 -> 24
            averageLength <= 300.0 -> 16
            else -> 8
        }
        val maxChars = when {
            averageLength <= 100.0 -> 12_000
            averageLength <= 300.0 -> 8_000
            else -> 4_000
        }
        if (texts.size <= maxItems && texts.sumOf { it.length } <= maxChars) return listOf(texts)

        val batches = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var chars = 0
        for (text in texts) {
            if (current.isNotEmpty() && (current.size >= maxItems || chars + text.length > maxChars)) {
                batches.add(current)
                current = ArrayList()
                chars = 0
            }
            current.add(text)
            chars += text.length
        }
        if (current.isNotEmpty()) batches.add(current)
        return batches
    }

    /**
     * Join OCR-wrapped lines with spaces. A small single-pass scanner avoids compiling
     * a regular expression and allocating intermediate line lists for every bubble.
     */
    private fun String.asSingleLine(): String {
        val out = StringBuilder(length)
        var pendingSpace = false
        var index = 0
        while (index < length) {
            val char = this[index]
            // OCR sometimes inserts a zero-width character inside a word; remove it,
            // rather than turning it into a visible space.
            if (char == '\u200B' || char == '\uFEFF') {
                index++
                continue
            }
            // Heal a word broken at the visual line edge: "con-\\ntinue" → "continue".
            if ((char == '-' || char == '‐' || char == '–') && out.isNotEmpty() && out.last().isLetter()) {
                var lookahead = index + 1
                var crossedLine = false
                while (lookahead < length && this[lookahead].isWhitespace()) {
                    if (this[lookahead] == '\n' || this[lookahead] == '\r') crossedLine = true
                    lookahead++
                }
                if (crossedLine && lookahead < length && this[lookahead].isLowerCase()) {
                    index = lookahead
                    pendingSpace = false
                    continue
                }
            }
            if (char.isWhitespace() || char == '\u00A0') {
                if (out.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) out.append(' ')
                out.append(char)
                pendingSpace = false
            }
            index++
        }
        return out.toString()
    }
}
