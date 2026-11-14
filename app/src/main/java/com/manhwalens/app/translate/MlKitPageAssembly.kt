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
}
