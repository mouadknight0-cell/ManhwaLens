package com.manhwalens.app.translate

/**
 * P0/C2 — the pure half of ML Kit page assembly.
 *
 * WHY this exists: the on-device engine has no notion of "this bubble came back untranslated".
 * It used to (a) substitute the **original English** when a block had no answer and (b) push
 * every finished block straight into the progressive painter, so an English echo could reach
 * the overlay before any page-level guard ran. Both decisions are pure functions now, so the
 * rules are unit-tested on the JVM while the engine keeps its batching, timeouts and threading.
 *
 * The rules themselves live in [ArabicValidator] — the shared guard for every engine — so ML Kit
 * and the remote engines are judged by exactly the same code.
 */
object MlKitPageAssembly {

    /**
     * Page-order result for a finished ML Kit page.
     *
     * A block with no answer stays **empty**: the caller's fallback (and the painter, which
     * keeps the original art for a blank block) handles it. The English source is never
     * substituted — that was the P0/C2 defect: untranslated dialogue presented as a successful
     * translation. Order and ids are preserved exactly.
     */
    fun ordered(
        blocks: List<TranslationBlock>,
        arabicById: Map<Int, String>
    ): List<TranslatedBlock> = blocks.map { b ->
        TranslatedBlock(id = b.id, english = b.english, arabic = arabicById[b.id].orEmpty())
    }

    /**
     * The progressive gateway: what the tap pipeline may paint immediately.
     *
     * Returns null for blank output and for output the shared guard rejects (an English echo, a
     * half-translated sentence, punctuation-only noise) — so a bad block never reaches the
     * painter through the direct callback. A valid Arabic bubble, or a legitimately preserved
     * standalone name, is returned unchanged, so progressive display is neither delayed nor
     * disabled.
     */
    fun progressivePayload(block: TranslatedBlock): TranslatedBlock? {
        if (block.arabic.isBlank()) return null
        if (ArabicValidator.issue(block.english, block.arabic) != null) return null
        return block
    }

    // ------------------------------------------------------------------ P2-06
    // Failure policy for a whole page. Before these rules existed the engine decided
    // "one bubble failed, so the page failed", threw away the answers it already had and
    // re-translated every bubble before giving up. The decisions are pure so the JVM tests
    // pin them while the engine keeps batching, timeouts and threading.

    /** A finished page: order preserved, unanswered bubbles empty, plus how many landed. */
    data class Assembly(val blocks: List<TranslatedBlock>, val translated: Int) {
        /** Ids that came back without any Arabic — retried, then left empty. */
        val unansweredIds: List<Int> get() = blocks.filter { it.arabic.isBlank() }.map { it.id }
    }

    /**
     * Assemble the page from whatever the engine answered.
     *
     * Every id in [blocks] appears exactly once and in page order; an id missing from
     * [arabicById] (or answered with blank text) becomes an **empty** bubble — the English
     * source is never substituted (P0/C2). A failure in one bubble cannot drop another.
     */
    fun assemble(blocks: List<TranslationBlock>, arabicById: Map<Int, String>): Assembly {
        val ordered = ordered(blocks, arabicById)
        return Assembly(ordered, ordered.count { it.arabic.isNotBlank() })
    }

    /**
     * Should the page be reported as a failure?
     *
     * Only when there was something to translate and **nothing** came back: a page with one
     * or more good bubbles is a partial success, and its empty bubbles are the caller's cue
     * to fall back per bubble. Reporting a "successful" page with no translation at all would
     * paint an empty overlay over the art and hide the problem.
     */
    fun mustFail(assembly: Assembly, requestedBlocks: Int): Boolean =
        requestedBlocks > 0 && assembly.translated == 0

    /**
     * Sequential recovery: retry **only** the bubbles without an answer, once each.
     *
     * Never re-runs a bubble that already succeeded (the old fallback re-translated the whole
     * page), and never invents text: [attempt] returning null leaves that bubble missing.
     * The callback is called as-is — a `CancellationException` thrown by the engine propagates
     * to the caller instead of being turned into an ordinary translation failure.
     */
    fun retryMissing(
        blocks: List<TranslationBlock>,
        answered: Map<Int, String>,
        attempt: (TranslationBlock) -> String?
    ): Map<Int, String> {
        val out = LinkedHashMap<Int, String>(answered.size + blocks.size)
        out.putAll(answered)
        for (b in blocks) {
            if (!out[b.id].isNullOrBlank()) continue
            val text = attempt(b)?.trim()
            if (!text.isNullOrBlank()) out[b.id] = text
        }
        return out
    }

    /**
     * Join the chunks of one long bubble, or null when any chunk is missing.
     *
     * A narration split into six chunks and answered five times is **not** a translation of the
     * bubble: presenting five sixths of it as complete would silently lose the ending. The
     * bubble stays empty instead, and the engine traces why.
     */
    fun joinChunks(parts: List<String?>): String? {
        if (parts.isEmpty()) return null
        if (parts.any { it.isNullOrBlank() }) return null
        return parts.joinToString(" ") { it!!.trim() }.trim().takeIf { it.isNotEmpty() }
    }
}
