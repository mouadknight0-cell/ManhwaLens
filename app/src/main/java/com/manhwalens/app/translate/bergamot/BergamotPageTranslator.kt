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
            val translated = runBlocking { engine.translate(engineInput) }
            PageTranslation.Success(
                blocks = blocks.zip(translated).map { (source, target) ->
                    TranslatedBlock(source.id, source.english, target)
                },
                engineId = engineId,
                elapsedMs = System.currentTimeMillis() - started,
                cacheHits = 0,
                cacheMisses = blocks.size
            )
        } catch (e: Exception) {
            PageTranslation.Failure(FailureReason.UNKNOWN, e.message ?: e.javaClass.simpleName, engineId)
        }
    }

    /**
     * Join OCR-wrapped lines with spaces. Collapse remaining whitespace so CRLF,
     * tabs and repeated spaces cannot reintroduce artificial sentence boundaries.
     */
    private fun String.asSingleLine(): String =
        lineSequence()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s+"), " ")
            .trim()
}
