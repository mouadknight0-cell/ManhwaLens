package com.manhwalens.app.translate

/**
 * Cache-aside decorator around any [Translator], three tiers (v0.11.0):
 *  1. in-memory [TranslationCache] (exact key: engine + style + prepared text);
 *  2. optional disk [PersistentTranslationCache] for earlier sessions;
 *  3. [TranslationMemo] — a fuzzy tier for SHORT strings, so a one-character OCR
 *     difference ("KRAK!!" vs "Kraak") does not cost an engine round-trip.
 *
 * Blocks fully served from any tier never reach the engine; misses go through the engine
 * as one batch with the same page semantics; engine output is written back to every tier.
 * Engine failure → NOTHING is cached and the failure is surfaced as-is. Blank input blocks
 * never reach the engine; they echo back as empty.
 *
 * v0.11.0 also owns the output post-processing every engine shares, so the rules live in
 * one place instead of per engine:
 *  - [ArabicValidator.clean] strips labels/quotes a model adds;
 *  - [BubbleTextRules.polishArabic] fixes Arabic punctuation;
 *  - [BubbleTextRules.tidyDialogue] collapses "هو هو هو" / "……" noise;
 *  - the quality guard rejects a bubble instead of failing the whole page; a rejected
 *    bubble is returned EMPTY (never an English echo), so the engine fallback can fill it.
 *
 * Keys are engine + style + normalized text.
 */
class CachingTranslator(
    private val delegate: Translator,
    private val cache: TranslationCache,
    private val persistent: PersistentTranslationCache? = null,
    private val style: String = "default",
    /** Progressive display hook — fired for every CACHE HIT (misses flow through the engine). */
    private val onBlockReady: ((TranslatedBlock) -> Unit)? = null,
    /**
     * Optional fuzzy tier (short strings only). OFF by default so a decorator instance is
     * self-contained and testable; [TranslatorFactory] passes the process-wide memo.
     */
    private val memo: TranslationMemo? = null,
    /**
     * P1 — authoritative name override applied to BOTH cache hits and fresh engine output:
     * `(originalEnglish, arabic) → arabic`. Wired to
     * [com.manhwalens.app.data.NameSession.rewrite], so a locked/confirmed series spelling
     * wins over a translation that was cached before the correction existed.
     *
     * Null (the default) leaves every existing caller byte-identical to v0.11.0.
     */
    private val pinnedOverride: ((String, String) -> String)? = null
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
        var fuzzyHits = 0
        for (b in blocks) {
            if (b.english.isBlank()) continue // echoed back empty below; never sent to engine
            val memHit = cache.get(engineId, b.english, style)
            val diskHit = if (memHit == null) persistent?.get(cache.key(engineId, b.english, style)) else null
            var hit = memHit ?: diskHit
            var fromFuzzy = false
            if (hit == null) {
                val fuzzy = memo?.get(cachePrepared(b.english))?.arabic
                if (fuzzy != null) {
                    hit = fuzzy
                    fromFuzzy = true
                    fuzzyHits++
                }
            }
            // P1: a cached translation may pre-date a name correction — repair it BEFORE the
            // quality guard sees it, so the guard judges the text we would actually paint.
            val rawHit = hit
            val nameOverride = pinnedOverride
            val repairedHit: String? = if (rawHit != null && nameOverride != null) {
                runCatching { nameOverride.invoke(b.english, rawHit) }.getOrDefault(rawHit)
            } else {
                rawHit
            }
            hit = repairedHit
            val issue = if (repairedHit != null && requiresArabicGuard) {
                ArabicValidator.issue(b.english, repairedHit)
            } else null
            if (repairedHit != null && issue == null) {
                cached[b.id] = repairedHit
                when {
                    diskHit != null -> {
                        // Promote the (possibly name-repaired) disk hit, so the correction sticks.
                        cache.put(engineId, b.english, repairedHit ?: diskHit, style)
                        diskHits++
                    }
                    fromFuzzy && repairedHit != null -> cache.put(engineId, b.english, repairedHit, style)
                }
                // A repaired plain memory hit must be written back too, otherwise every capture
                // re-repairs the same stale entry.
                if (repairedHit != rawHit) cache.put(engineId, b.english, repairedHit, style)
            } else {
                if (hit != null && issue != null) {
                    val key = cache.key(engineId, b.english, style)
                    cache.remove(engineId, b.english, style)
                    persistent?.remove(key)
                    memo?.remove(cachePrepared(b.english))
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
        if (fuzzyHits > 0) {
            com.manhwalens.app.util.PipelineTracer.span("fuzzy memo", 0, "$fuzzyHits block(s) served from a near match")
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
                        // One post-processing pipeline for every engine; cached text is already clean.
                        raw.copy(arabic = postProcess(raw.arabic))
                    }
                    // Per-bubble triage: a rejected bubble becomes EMPTY (never an English
                    // echo on screen), the readable bubbles of the same reply are kept.
                    val rejectedIds = if (requiresArabicGuard) {
                        polished.mapNotNull { block ->
                            if (block.arabic.isBlank()) null
                            else ArabicValidator.issue(block.english, block.arabic)?.let { block.id to it }
                        }
                    } else emptyList()
                    val usable = polished.count { tb ->
                        tb.arabic.isNotBlank() && rejectedIds.none { it.first == tb.id }
                    }
                    if (rejectedIds.isNotEmpty()) {
                        val summary = rejectedIds.take(4).joinToString { "#${it.first}:${it.second.name.lowercase()}" }
                        com.manhwalens.app.util.PipelineTracer.span(
                            "quality guard", 0,
                            "dropped ${rejectedIds.size}/${polished.size} block(s) ($summary)"
                        )
                        // NOTHING usable → the whole reply is an echo/garbage; report a page
                        // failure so the caller falls back to the on-device draft.
                        if (usable == 0) {
                            return PageTranslation.Failure(
                                FailureReason.PARSE_ERROR,
                                "Translation quality check rejected ${rejectedIds.size} block(s) ($summary)",
                                engineId
                            )
                        }
                    }

                    val toPersist = LinkedHashMap<String, String>(polished.size)
                    for (tb in polished) {
                        val isRejected = rejectedIds.any { it.first == tb.id }
                        // P1: the engine's own output gets the same authoritative override, so
                        // a model that spells a locked name differently cannot reach the screen.
                        val source = tb.arabic
                        val nameOverride = pinnedOverride
                        val finalArabic = if (!isRejected && source.isNotBlank() && nameOverride != null) {
                            runCatching { nameOverride.invoke(tb.english, source) }.getOrDefault(source)
                        } else {
                            source
                        }
                        translatedById[tb.id] = if (isRejected) "" else finalArabic
                        if (finalArabic.isNotBlank() && !isRejected) {
                            cache.put(engineId, tb.english, finalArabic, style)
                            toPersist[cache.key(engineId, tb.english, style)] = finalArabic
                            memo?.put(cachePrepared(tb.english), finalArabic)
                        }
                    }
                    if (toPersist.isNotEmpty()) persistent?.putAll(toPersist)
                }
            }
        }

        // Never paint English: a block the engine could not answer (or whose answer the
        // guard rejected) comes back EMPTY, so the caller keeps/fills the on-device draft.
        val ordered = blocks.map { b ->
            val arabic = translatedById[b.id] ?: ""
            TranslatedBlock(id = b.id, english = b.english, arabic = arabic)
        }
        return PageTranslation.Success(
            blocks = ordered,
            engineId = engineId,
            elapsedMs = System.currentTimeMillis() - start,
            cacheHits = cacheHits,
            cacheMisses = misses.size
        )
    }

    /** Exact text the engine is shown (glossary-prepared) — same string the caches key on. */
    private fun cachePrepared(source: String): String =
        runCatching { Glossary.cacheText(source) }.getOrDefault(source)

    /** Shared output hygiene: noise, punctuation, duplicated words. */
    private fun postProcess(arabic: String): String {
        if (arabic.isBlank()) return arabic
        return BubbleTextRules.tidyDialogue(BubbleTextRules.polishArabic(ArabicValidator.clean(arabic)))
    }

    companion object {
        /**
         * Process-wide fuzzy memo, one per engine+style namespace so a device translation
         * can never be served where a cloud translation was requested (and vice versa).
         */
        private val memos = java.util.concurrent.ConcurrentHashMap<String, TranslationMemo>()

        fun memoFor(engineId: String, style: String): TranslationMemo =
            memos.getOrPut("$engineId|$style") { TranslationMemo() }

        fun resetMemos() {
            memos.values.forEach { runCatching { it.clear() } }
        }
    }
}
