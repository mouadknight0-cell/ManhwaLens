package com.manhwalens.app.translate

/**
 * Cache-aside decorator around any [Translator], two tiers:
 *  - blocks fully served from cache never reach the engine;
 *  - misses go through the engine as one batch with the same page semantics;
 *  - engine output is written back to both tiers;
 *  - engine failure → NOTHING is cached and the failure is surfaced as-is;
 *  - blank input blocks never reach the engine; they echo back as empty.
 *
 * Tier 1 is the in-memory LRU [TranslationCache]; tier 2 (optional) is the
 * disk-backed [PersistentTranslationCache] — a string translated in an
 * earlier session never hits the engine again.
 *
 * Keys are engine + style + normalized text.
 */
class CachingTranslator(
    private val delegate: Translator,
    private val cache: TranslationCache,
    private val persistent: PersistentTranslationCache? = null,
    private val style: String = "default",
    /** Progressive display hook — fired for every CACHE HIT (misses flow through the engine). */
    private val onBlockReady: ((TranslatedBlock) -> Unit)? = null
) : Translator {

    override val engineId: String get() = delegate.engineId

    /** Remote caches from an older version must not bypass the output-quality guard. */
    private val requiresArabicGuard: Boolean
        get() = engineId == "gemini" || engineId == "libre_translate"

    override fun translatePage(blocks: List<TranslationBlock>): PageTranslation {
        if (blocks.isEmpty()) {
            return PageTranslation.Failure(
                FailureReason.EMPTY_INPUT,
                "No blocks to translate",
                engineId
            )
        }
        val start = System.currentTimeMillis()

        // 1) Serve what we can from cache (engine+style namespaced, normalized).
        val cached = LinkedHashMap<Int, String>(blocks.size)
        val misses = ArrayList<TranslationBlock>(blocks.size)
        var diskHits = 0
        for (b in blocks) {
            if (b.english.isBlank()) continue // echoed back empty below; never sent to engine
            val memHit = cache.get(engineId, b.english, style)
            val diskHit = if (memHit == null) persistent?.get(cache.key(engineId, b.english, style)) else null
            val hit = memHit ?: diskHit
            val issue = if (hit != null && requiresArabicGuard) {
                ArabicValidator.issue(b.english, hit)
            } else null
            if (hit != null && issue == null) {
                cached[b.id] = hit
                if (diskHit != null) {
                    cache.put(engineId, b.english, diskHit, style) // promote a valid disk hit to memory
                    diskHits++
                }
            } else {
                if (hit != null && issue != null) {
                    val key = cache.key(engineId, b.english, style)
                    cache.remove(engineId, b.english, style)
                    persistent?.remove(key)
                }
                // A stale pre-guard remote entry is a cache MISS, never a visible translation.
                misses.add(b)
            }
        }
        if (onBlockReady != null && cached.isNotEmpty()) {
            for ((id, arabic) in cached) {
                if (arabic.isNotBlank()) {
                    val english = blocks.firstOrNull { it.id == id }?.english ?: ""
                    runCatching { onBlockReady(TranslatedBlock(id, english, arabic)) }
                }
            }
        }
        val cacheHits = cached.size
        if (diskHits > 0) {
            com.manhwalens.app.util.PipelineTracer.span("persistent cache", 0, "$diskHits block(s) served from disk")
        }

        // 2) Translate the rest in one batch.
        val translatedById = HashMap<Int, String>(blocks.size)
        translatedById.putAll(cached)

        if (misses.isNotEmpty()) {
            when (val result = delegate.translatePage(misses)) {
                is PageTranslation.Failure -> {
                    return PageTranslation.Failure(result.reason, result.message, engineId)
                }
                is PageTranslation.Success -> {
                    val polished = result.blocks.map { raw ->
                        // v0.9.14: Arabic punctuation (؟ ،) for every engine, cached already clean.
                        raw.copy(arabic = BubbleTextRules.polishArabic(raw.arabic))
                    }
                    val rejected = if (requiresArabicGuard) {
                        polished.mapNotNull { block ->
                            if (block.arabic.isBlank()) null
                            else ArabicValidator.issue(block.english, block.arabic)?.let { block.id to it }
                        }
                    } else emptyList()
                    if (rejected.isNotEmpty()) {
                        val summary = rejected.take(4).joinToString { "#${it.first}:${it.second.name.lowercase()}" }
                        return PageTranslation.Failure(
                            FailureReason.PARSE_ERROR,
                            "Translation quality check rejected ${rejected.size} block(s) ($summary)",
                            engineId
                        )
                    }

                    val toPersist = LinkedHashMap<String, String>(polished.size)
                    for (tb in polished) {
                        translatedById[tb.id] = tb.arabic
                        if (tb.arabic.isNotBlank()) {
                            cache.put(engineId, tb.english, tb.arabic, style)
                            toPersist[cache.key(engineId, tb.english, style)] = tb.arabic
                        }
                    }
                    if (toPersist.isNotEmpty()) persistent?.putAll(toPersist)
                }
            }
        }

        val ordered = blocks.map { b ->
            TranslatedBlock(
                id = b.id,
                english = b.english,
                arabic = translatedById[b.id] ?: if (b.english.isBlank()) "" else b.english // defensive: never crash the page
            )
        }
        return PageTranslation.Success(
            blocks = ordered,
            engineId = engineId,
            elapsedMs = System.currentTimeMillis() - start,
            cacheHits = cacheHits,
            cacheMisses = misses.size
        )
    }
}
