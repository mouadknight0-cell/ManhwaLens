package com.manhwalens.app.translate.bergamot

import android.content.Context
import com.manhwalens.app.translate.FailureReason
import com.manhwalens.app.translate.PageTranslation
import com.manhwalens.app.translate.TranslatedBlock
import com.manhwalens.app.translate.TranslationBlock
import com.manhwalens.app.translate.Translator
import kotlinx.coroutines.runBlocking

/** Blocking adapter matching ManhwaLens' existing Translator contract. */
internal class BergamotPageTranslator(context: Context) : Translator {
    private val app = context.applicationContext
    private val engine = BergamotEnArTranslator(app)
    override val engineId: String = "bergamot_en_ar"

    override fun translatePage(blocks: List<TranslationBlock>): PageTranslation {
        if (blocks.isEmpty()) return PageTranslation.Failure(
            FailureReason.EMPTY_INPUT, "No blocks to translate", engineId
        )
        if (!BergamotEnArModel.isInstalled(app)) return PageTranslation.Failure(
            FailureReason.MODEL_DOWNLOAD_FAILED,
            "Download the Bergamot English→Arabic model in Settings first",
            engineId
        )
        val started = System.currentTimeMillis()
        return try {
            // OCR line wrapping is visual layout, not sentence structure. Send each
            // bubble to Bergamot as one continuous line, while keeping source.english
            // untouched below so diagnostics/cache identity retain the original OCR.
            val engineInput = blocks.map { block -> block.english.asSingleLine() }

            // Repeated sound effects and dialogue often occur more than once on a page.
            // Translate each exact normalized bubble only once, then fan its result back
            // to every original block ID. This reduces native inference work without
            // merging different bubbles or changing their order/identity.
            val uniqueIndexes = LinkedHashMap<String, Int>(engineInput.size)
            val uniqueTexts = ArrayList<String>(engineInput.size)
            val inputIndexes = engineInput.map { text ->
                uniqueIndexes[text] ?: run {
                    val index = uniqueTexts.size
                    uniqueIndexes[text] = index
                    uniqueTexts.add(text)
                    index
                }
            }

            val uniqueOutputs = if (uniqueTexts.isEmpty()) {
                emptyList()
            } else {
                runBlocking { engine.translate(uniqueTexts) }
            }
            check(uniqueOutputs.size == uniqueTexts.size) {
                "Bergamot returned ${uniqueOutputs.size} translations for ${uniqueTexts.size} unique bubbles"
            }

            PageTranslation.Success(
                blocks = blocks.mapIndexed { index, source ->
                    TranslatedBlock(source.id, source.english, uniqueOutputs[inputIndexes[index]])
                },
                engineId = engineId,
                elapsedMs = System.currentTimeMillis() - started,
                cacheHits = 0,
                cacheMisses = uniqueTexts.size
            )
        } catch (e: Exception) {
            PageTranslation.Failure(FailureReason.UNKNOWN, e.message ?: e.javaClass.simpleName, engineId)
        }
    }

    /**
     * Join OCR-wrapped lines with spaces. A small single-pass scanner avoids compiling
     * a regular expression and allocating intermediate line lists for every bubble.
     */
    private fun String.asSingleLine(): String {
        val out = StringBuilder(length)
        var pendingSpace = false
        for (char in this) {
            if (char.isWhitespace()) {
                if (out.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) out.append(' ')
                out.append(char)
                pendingSpace = false
            }
        }
        return out.toString()
    }
}
