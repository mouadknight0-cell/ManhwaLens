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
            val engineInput = blocks.map { block -> BergamotTextPreparation.asSingleLine(block.english) }

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
            val batches = BergamotTextPreparation.adaptiveBatches(uniqueTexts)
            var nativeMs = 0L
            var outputOffset = 0
            batches.forEachIndexed { batchIndex, batch ->
                val batchStartedNs = SystemClock.elapsedRealtimeNanos()
                val translated = runBlocking { engine.translate(batch) }
                check(translated.size == batch.size) {
                    "Bergamot returned ${translated.size} translations for ${batch.size} unique bubbles"
                }
                val elapsed = (SystemClock.elapsedRealtimeNanos() - batchStartedNs) / 1_000_000L
                nativeMs += elapsed
                translated.forEachIndexed { index, value -> outputs[outputOffset + index] = value }
                outputOffset += translated.size
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


}
