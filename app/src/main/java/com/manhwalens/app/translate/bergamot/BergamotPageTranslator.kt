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
            val translated = runBlocking { engine.translate(blocks.map { it.english }) }
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

}
