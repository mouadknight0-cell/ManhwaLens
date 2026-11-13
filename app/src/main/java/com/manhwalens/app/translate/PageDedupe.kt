package com.manhwalens.app.translate

/**
 * Per-page dedupe for identical bubble strings: the engine is asked to
 * translate each unique string ONCE; the result is fanned back out to every
 * bubble that contained it (bubbles frequently repeat e.g. "!!" or a narrator
 * line). Pure Kotlin so it is unit-testable.
 */
object PageDedupe {

    data class Plan(
        /** Blocks to send to the engine (one per unique normalized string). */
        val unique: List<TranslationBlock>,
        /** normalizedKey -> ids of the original blocks that share that string. */
        val keyToIds: Map<String, List<Int>>,
        /** id -> normalizedKey for expansion. */
        val idToKey: Map<Int, String>
    ) {
        val removedCount: Int get() = idToKey.size - unique.size
    }

    fun plan(blocks: List<TranslationBlock>): Plan {
        val keyToIds = LinkedHashMap<String, MutableList<Int>>()
        val uniqueByKey = LinkedHashMap<String, TranslationBlock>()
        val idToKey = LinkedHashMap<Int, String>()
        for (b in blocks) {
            val key = TranslationCache.normalize(b.english).lowercase()
            idToKey[b.id] = key
            uniqueByKey.putIfAbsent(key, b)
            keyToIds.getOrPut(key) { ArrayList() }.add(b.id)
        }
        return Plan(uniqueByKey.values.toList(), keyToIds, idToKey)
    }

    /** Expand engine results for the unique blocks back to ALL original blocks. */
    fun expand(plan: Plan, original: List<TranslationBlock>, translatedUnique: List<TranslatedBlock>): List<TranslatedBlock> {
        val byId = translatedUnique.associateBy({ it.id }, { it.arabic })
        val byKeyArabic = HashMap<String, String>()
        for (b in plan.unique) {
            val arabic = byId[b.id] ?: continue
            byKeyArabic[TranslationCache.normalize(b.english).lowercase()] = arabic
        }
        return original.map { b ->
            val key = plan.idToKey[b.id] ?: TranslationCache.normalize(b.english).lowercase()
            TranslatedBlock(b.id, b.english, byKeyArabic[key] ?: "")
        }
    }
}
