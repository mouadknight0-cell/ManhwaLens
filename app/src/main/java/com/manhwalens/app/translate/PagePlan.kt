package com.manhwalens.app.translate

/**
 * Per-page translation planning for speed (v0.11.0).
 *
 * The tap/Live pipeline used to hand every OCR block straight to the engine. Two costs
 * showed up in the pipeline trace on real devices:
 *  1. the SAME source string appears several times per page ("......", "!!", a repeated
 *     line) and was translated once per bubble;
 *  2. callers that already deduped kept doing so AFTER glossary preparation, so two
 *     spellings of one name ("Wei Lin" and "wei lin") became two engine calls.
 *
 * [prepare] runs before the cache and the engine:
 *  - drops blocks that carry no meaning ([MeaninglessText]) — they never reach the engine;
 *  - dedupes on the normalized source text, keeping a stable key per unique string;
 *  - returns an expansion map so the caller restores one Arabic line per original bubble.
 *
 * Pure Kotlin (JVM-tested).
 */
object PagePlan {

    /** One engine-facing block: the unique source text and the ids it stands for. */
    data class Item(
        val key: String,
        val text: String,
        val ids: List<Int>
    )

    data class Plan(
        val items: List<Item>,
        /** Original block id → normalized key (for expansion). */
        val idToKey: Map<Int, String>,
        /** Ids dropped as meaningless, with the reason, for the debug report. */
        val dropped: List<Pair<Int, com.manhwalens.app.ocr.MeaninglessText.Reason>>,
        /** Original block count before planning. */
        val originalCount: Int
    ) {
        val uniqueCount: Int get() = items.size
        val droppedCount: Int get() = dropped.size
    }

    /**
     * Why a block was dropped. Nested aliases are not allowed in Kotlin, so the OCR enum
     * is referenced through this shadow type — call sites keep reading `MeaninglessReason`.
     */
    class MeaninglessReason private constructor() {
        companion object {
            val PUNCTUATION_ONLY get() = com.manhwalens.app.ocr.MeaninglessText.Reason.PUNCTUATION_ONLY
            val DIGITS_OR_SYMBOLS_ONLY get() = com.manhwalens.app.ocr.MeaninglessText.Reason.DIGITS_OR_SYMBOLS_ONLY
            val SOUND_EFFECT get() = com.manhwalens.app.ocr.MeaninglessText.Reason.SOUND_EFFECT
            val WATERMARK get() = com.manhwalens.app.ocr.MeaninglessText.Reason.WATERMARK
            val APP_CHROME get() = com.manhwalens.app.ocr.MeaninglessText.Reason.APP_CHROME
            val CHAPTER_HEADER get() = com.manhwalens.app.ocr.MeaninglessText.Reason.CHAPTER_HEADER
            val ALREADY_ARABIC get() = com.manhwalens.app.ocr.MeaninglessText.Reason.ALREADY_ARABIC
            val OCR_FRAGMENT get() = com.manhwalens.app.ocr.MeaninglessText.Reason.OCR_FRAGMENT
        }
    }

    /**
     * @param blocks  OCR blocks (id + english text)
     * @param ownStrings strings that belong to this app's own overlay
     * @param preprocess optional text transform applied BEFORE dedupe/bucketing
     *        (the pipeline passes [Glossary.prepare] so cache keys match what the engine sees)
     */
    fun prepare(
        blocks: List<TranslationBlock>,
        ownStrings: Set<String> = emptySet(),
        preprocess: (String) -> String = { it }
    ): Plan {
        val idToKey = LinkedHashMap<Int, String>(blocks.size)
        val byKey = LinkedHashMap<String, Item>()
        val dropped = ArrayList<Pair<Int, com.manhwalens.app.ocr.MeaninglessText.Reason>>()
        for (b in blocks) {
            val prepared = runCatching { preprocess(b.english) }.getOrDefault(b.english)
            val verdict = com.manhwalens.app.ocr.MeaninglessText.classify(prepared, ownStrings)
            if (!verdict.keep && prepared.isNotBlank()) {
                dropped += b.id to verdict.reason
                continue
            }
            if (prepared.isBlank()) {
                dropped += b.id to MeaninglessReason.OCR_FRAGMENT
                continue
            }
            val key = keyOf(prepared)
            idToKey[b.id] = key
            val existing = byKey[key]
            byKey[key] = if (existing == null) {
                Item(key, prepared.trim(), listOf(b.id))
            } else {
                existing.copy(ids = existing.ids + b.id)
            }
        }
        return Plan(byKey.values.toList(), idToKey, dropped, blocks.size)
    }

    /** Stable dedupe key: case-folded, whitespace-collapsed, punctuation at the edges dropped. */
    fun keyOf(text: String): String =
        text.trim()
            .replace(Regex("\\s+"), " ")
            .trim('.', '…', '!', '?', ',', ';', ':', ' ')
            .lowercase()

    /**
     * Fill blanks in an engine result: an id that came back EMPTY (the guard rejected it)
     * must not keep the English text — the overlay would paint it. Returns the same list
     * with those entries cleared, which is what the caller shows.
     */
    fun sanitizeEnglishEcho(translated: List<TranslatedBlock>): List<TranslatedBlock> =
        translated.map { tb ->
            if (tb.arabic.isBlank()) tb.copy(arabic = "") else tb
        }

    /**
     * One [TranslationBlock] per unique item, in page order. The id is the FIRST original
     * block id of the item, so progressive listeners (which paint by block id) keep working
     * — a deduped string is answered once under that id and fanned out by [arabicById].
     */
    fun engineBlocks(plan: Plan): List<TranslationBlock> =
        plan.items.map { item -> TranslationBlock(item.ids.first(), item.text) }

    /**
     * P2-02 — the order a REMOTE engine must see: page / reading order.
     *
     * A remote prompt (Gemini) tells the model that consecutive blocks are one page's
     * consecutive dialogue, so the list handed to it — and therefore the chunk boundaries its
     * chunker computes — has to be page order ([engineBlocks]). Sorting that list by how good a
     * bubble looks (short, large, central) scrambles the reading sequence and costs the model
     * the context the prompt promises.
     */
    fun remoteOrder(plan: Plan): List<TranslationBlock> = engineBlocks(plan)

    /**
     * P2-02 — the order the LOCAL progressive pass paints in: visual priority (rank from
     * [BlockPriority], lower is earlier; unranked ids last). This is what makes the biggest,
     * most useful bubbles appear first on a slow device, and it must never reach a remote
     * request — it is not reading order.
     */
    fun progressiveOrder(plan: Plan, rankById: Map<Int, Int>): List<TranslationBlock> =
        engineBlocks(plan).sortedBy { rankById[it.id] ?: Int.MAX_VALUE }

    /**
     * Restore every original id from the engine answers (keyed by representative block id).
     * Blocks dropped as meaningless, and answers the guard rejected, come back EMPTY so
     * the overlay paints nothing over them (it must never paint English).
     */
    fun expand(
        plan: Plan,
        translated: List<TranslatedBlock>,
        englishById: Map<Int, String>
    ): List<TranslatedBlock> {
        val arabic = arabicById(plan, translated)
        val out = ArrayList<TranslatedBlock>(plan.idToKey.size)
        for ((id, _) in plan.idToKey) {
            out += TranslatedBlock(id, englishById[id] ?: "", arabic[id] ?: "")
        }
        return out
    }

    /**
     * Arabic for every original block id: a block whose answer is missing, blank, or was
     * rejected by the guard stays ABSENT (the caller then paints nothing — never English),
     * and a block dropped as meaningless is absent too.
     */
    fun arabicById(
        plan: Plan,
        translated: List<TranslatedBlock>
    ): Map<Int, String> {
        // Engine answers come back under each item's representative (first) block id.
        val byId = HashMap<Int, String>(translated.size)
        for (t in translated) if (t.arabic.isNotBlank()) byId.putIfAbsent(t.id, t.arabic)
        val out = LinkedHashMap<Int, String>(plan.idToKey.size)
        for (item in plan.items) {
            val arabic = byId[item.ids.first()] ?: continue
            for (id in item.ids) out[id] = arabic
        }
        return out
    }

    /**
     * Synthetic engine blocks for the items that are NOT already covered by [known]
     * (original id → arabic). Live mode passes the bubbles it already painted, so a
     * re-scanned page asks the engine only about genuinely new text.
     */
    fun pendingEngineBlocks(plan: Plan, known: Map<Int, String>): List<TranslationBlock> =
        plan.items.mapNotNull { item ->
            // An item is known when at least one of its ids already has Arabic.
            val covered = item.ids.any { known[it]?.isNotBlank() == true }
            if (covered) null else TranslationBlock(item.ids.first(), item.text)
        }

    /** Compact one-line summary for the pipeline trace. */
    fun describe(plan: Plan): String =
        "${plan.originalCount} blocks → ${plan.uniqueCount} unique" +
            (if (plan.uniqueCount < plan.originalCount - plan.droppedCount) {
                ", ${plan.originalCount - plan.droppedCount - plan.uniqueCount} repeated"
            } else "") +
            (if (plan.droppedCount > 0) ", ${plan.droppedCount} dropped" else "")
}
