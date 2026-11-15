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
    /** Progressive display hook — fired for exact/fuzzy memo hits (engine misses emit separately). */
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
    private val pinnedOverride: ((String, String) -> String)? = null,
    /** Opt-in local observer for final source/translation pairs, including cache hits and rejected outputs. */
    private val onTranslationPair: ((TranslationPairTrace) -> Unit)? = null
) : Translator {

    override val engineId: String get() = delegate.engineId

    /**
     * Caches from an older version must not bypass the output-quality guard.
     *
     * P0/C2: ML Kit is included. An on-device model that echoes English is exactly the failure
     * this guard exists for; excluding it meant an echo was cached (memory, disk and fuzzy tiers)
     * and re-served for the rest of the chapter.
     */
    private val requiresArabicGuard: Boolean
        get() = engineId == "gemini" || engineId == "ml_kit_en_ar" || engineId == "bergamot_en_ar"

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
        val fuzzyCachedIds = HashSet<Int>()
        val misses = ArrayList<TranslationBlock>(blocks.size)
        var diskHits = 0
        var fuzzyHits = 0
        for (b in blocks) {
            if (b.english.isBlank()) continue // echoed back empty below; never sent to engine
            val memHit = cache.get(engineId, b.english, style)
            val diskHit = if (memHit == null) persistent?.get(cache.key(engineId, b.english, style)) else null
            var hit = memHit ?: diskHit
            var fromFuzzy = false
            var fromExactMemo = false
            if (hit == null) {
                val memoHit = memo?.get(cachePrepared(b.english))
                if (memoHit != null) {
                    hit = memoHit.arabic
                    fromFuzzy = !memoHit.exact
                    fromExactMemo = memoHit.exact
                    if (fromFuzzy) {
                        fuzzyHits++
                        fuzzyCachedIds.add(b.id)
                    }
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
            val dialogueRepairedHit = repairedHit?.let { ShortDialogueOverrides.apply(b.english, it) }
            val issue = if (dialogueRepairedHit != null && requiresArabicGuard) {
                ArabicValidator.issue(b.english, dialogueRepairedHit)
            } else null
            if (dialogueRepairedHit != null && issue == null) {
                cached[b.id] = dialogueRepairedHit
                when {
                    diskHit != null || fromExactMemo -> {
                        // Promote only source-exact disk/memo hits. A fuzzy near-match must
                        // never become an exact key for a different OCR string.
                        cache.put(engineId, b.english, dialogueRepairedHit, style)
                        if (diskHit != null) diskHits++
                    }
                }
                // Persist corrections only for source-exact cache entries. A fuzzy result
                // remains in the fuzzy tier and must be re-evaluated for each new OCR variant.
                // Never promote an approximate decision into an exact key for different text.
                if (!fromFuzzy && dialogueRepairedHit != rawHit) {
                    cache.put(engineId, b.english, dialogueRepairedHit, style)
                }
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
        cached.forEach { (id, arabic) ->
            val source = blocks.firstOrNull { it.id == id }?.english.orEmpty()
            runCatching {
                val isFuzzy = id in fuzzyCachedIds
                onTranslationPair?.invoke(
                    TranslationPairTrace(
                        id, source, arabic, arabic, engineId,
                        if (isFuzzy) "FUZZY_HIT" else "CACHE_HIT",
                        if (isFuzzy) "served from fuzzy memo" else "served from exact cache",
                        0L
                    )
                )
            }
        }
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
                    misses.forEach { block ->
                        runCatching {
                            onTranslationPair?.invoke(TranslationPairTrace(block.id, block.english, "", "", engineId, "ENGINE_FAILURE", result.reason.name, 0L))
                        }
                    }
                    return PageTranslation.Failure(result.reason, result.message, engineId)
                }
                is PageTranslation.Success -> {
                    val polished = result.blocks.map { raw ->
                        // Apply authoritative series-name corrections BEFORE the quality guard,
                        // matching the cache-hit path. A preserved name such as "Will" must not
                        // be mistaken for an untranslated common English word.
                        val cleaned = TranslationOutputSanitizer.clean(raw.arabic)
                        val nameOverride = pinnedOverride
                        val nameRepaired = if (cleaned.isNotBlank() && nameOverride != null) {
                            runCatching { nameOverride.invoke(raw.english, cleaned) }.getOrDefault(cleaned)
                        } else {
                            cleaned
                        }
                        // Source-exact dialogue repairs run last so they agree with cache hits
                        // and cannot be undone by a character-name callback.
                        val corrected = ShortDialogueOverrides.apply(raw.english, nameRepaired)
                        raw.copy(arabic = corrected)
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
                    val rejectedById = rejectedIds.toMap()
                    polished.forEach { tb ->
                        val issue = rejectedById[tb.id]
                        runCatching {
                            onTranslationPair?.invoke(TranslationPairTrace(
                                tb.id, tb.english, if (issue == null) tb.arabic else "", tb.arabic,
                                engineId,
                                if (issue == null && tb.arabic.isNotBlank()) "OK" else if (issue != null) "QUALITY_REJECTED" else "EMPTY",
                                issue?.name ?: if (tb.arabic.isBlank()) "empty translation" else "",
                                result.elapsedMs
                            ))
                        }
                    }
                    val returnedIds = polished.map { it.id }.toHashSet()
                    val emptyCount = polished.count { it.arabic.isBlank() }
                    val missingCount = misses.size - returnedIds.size
                    val acceptancePercent = if (misses.isEmpty()) 100 else (usable * 100 / misses.size)
                    com.manhwalens.app.util.PipelineTracer.span(
                        "translation quality",
                        0,
                        "accepted=$usable rejected=${rejectedIds.size} empty=$emptyCount missing=$missingCount acceptance=${acceptancePercent}% (rule-based; not semantic score)"
                    )
                    misses.filterNot { it.id in returnedIds }.forEach { block ->
                        runCatching {
                            onTranslationPair?.invoke(TranslationPairTrace(
                                block.id, block.english, "", "", engineId, "MISSING_RESULT",
                                "engine returned no block for this id", result.elapsedMs
                            ))
                        }
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
                        // The authoritative name override was already applied before the
                        // quality guard above, so persistence and display use that judged value.
                        val finalArabic = tb.arabic
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
