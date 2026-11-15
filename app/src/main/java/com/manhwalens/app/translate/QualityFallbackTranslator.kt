package com.manhwalens.app.translate

/**
 * Offline quality fallback for the experimental Bergamot engine.
 *
 * Bergamot remains the selected primary engine. When its shared output guard withholds a
 * bubble (represented as blank Arabic), only those bubbles are sent to the already-installed
 * on-device ML Kit translator. No network engine is involved and accepted Bergamot bubbles
 * are never retransmitted.
 */
internal class QualityFallbackTranslator(
    private val primary: Translator,
    private val fallback: Translator,
    private val onFallback: ((requested: Int, elapsedMs: Long, filled: Int) -> Unit)? = null
) : Translator {

    override val engineId: String
        get() = primary.engineId

    override fun translatePage(blocks: List<TranslationBlock>): PageTranslation {
        if (blocks.isEmpty()) return primary.translatePage(blocks)

        return when (val first = primary.translatePage(blocks)) {
            is PageTranslation.Failure -> {
                when (val second = fallback.translatePage(blocks)) {
                    is PageTranslation.Success -> {
                        onFallback?.invoke(blocks.size, second.elapsedMs, second.blocks.count { it.arabic.isNotBlank() })
                        second.copy(engineId = "${primary.engineId}+ml_kit_fallback")
                    }
                    is PageTranslation.Failure -> first
                }
            }
            is PageTranslation.Success -> {
                val primaryById = first.blocks.associateBy { it.id }
                val missing = blocks.filter { primaryById[it.id]?.arabic.isNullOrBlank() }
                if (missing.isEmpty()) return first

                when (val second = fallback.translatePage(missing)) {
                    is PageTranslation.Failure -> {
                        onFallback?.invoke(missing.size, 0L, 0)
                        first
                    }
                    is PageTranslation.Success -> {
                        val fallbackById = second.blocks.associateBy { it.id }
                        var filled = 0
                        val merged = blocks.map { original ->
                            val primaryText = primaryById[original.id]?.arabic.orEmpty()
                            if (primaryText.isNotBlank()) {
                                TranslatedBlock(original.id, original.english, primaryText)
                            } else {
                                val fallbackText = fallbackById[original.id]?.arabic.orEmpty()
                                if (fallbackText.isNotBlank()) filled++
                                TranslatedBlock(original.id, original.english, fallbackText)
                            }
                        }
                        onFallback?.invoke(missing.size, second.elapsedMs, filled)
                        first.copy(
                            blocks = merged,
                            engineId = if (filled > 0) "${primary.engineId}+ml_kit_fallback" else primary.engineId,
                            elapsedMs = first.elapsedMs + second.elapsedMs,
                            cacheHits = first.cacheHits + second.cacheHits,
                            cacheMisses = first.cacheMisses + second.cacheMisses
                        )
                    }
                }
            }
        }
    }
}
