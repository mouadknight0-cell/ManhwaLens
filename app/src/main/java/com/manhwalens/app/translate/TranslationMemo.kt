package com.manhwalens.app.translate

/**
 * Bounded, thread-safe translation memory with a **fuzzy second tier** (v0.11.0).
 *
 * Why a fuzzy tier: the same line comes back from OCR with small differences all the time —
 * a hyphenation healed differently, a bubble split into two lines, "Wei Lin" spelled "Wei
 * Lin," with a trailing comma, or one word dropped by the recognizer. A strict hash misses
 * all of those and pays a full engine round-trip (the slowest part of a Live frame).
 *
 * The memo matches in two steps:
 *  1. **exact key** — the normalized, glossary-prepared source (same string → same Arabic);
 *  2. **shape key** — the source with vowels, doubled letters, punctuation and case removed.
 *     Only consulted for SHORT strings (≤ [fuzzyMaxChars]), and only when the candidate is
 *     itself short, so two different long sentences can never collide.
 *
 * Both tiers keep their own LRU bound, so a page full of new dialogue cannot evict the hot
 * short strings (SFX, "......", honorifics) that Live mode re-reads every frame.
 *
 * Pure Kotlin (JVM-tested); the disk tier stays [PersistentTranslationCache].
 */
class TranslationMemo(
    private val exactCapacity: Int = 1500,
    private val fuzzyCapacity: Int = 600,
    private val fuzzyMaxChars: Int = 40
) {

    private class Lru<V>(private val max: Int) {
        private val map = object : LinkedHashMap<String, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > max
        }
        fun get(k: String): V? = map[k]
        fun put(k: String, v: V) { map[k] = v }
        fun remove(k: String) { map.remove(k) }
        fun size(): Int = map.size
        fun clear() = map.clear()
    }

    private val exact = Lru<String>(exactCapacity)
    private val fuzzy = Lru<String>(fuzzyCapacity)
    private val lock = Any()

    data class Hit(val arabic: String, val exact: Boolean)

    /**
     * @param source the text as the ENGINE will see it (glossary-prepared), or the raw
     *        source when the caller does not prepare — the memo normalizes either way.
     */
    fun get(source: String): Hit? {
        val key = key(source)
        synchronized(lock) {
            exact.get(key)?.let { return Hit(it, true) }
            if (source.length > fuzzyMaxChars) return null
            val shape = shapeOf(key)
            if (shape.isEmpty()) return null
            val hit = fuzzy.get(shape) ?: return null
            return Hit(hit, false)
        }
    }

    fun put(source: String, arabic: String) {
        if (arabic.isBlank()) return
        val key = key(source)
        synchronized(lock) {
            exact.put(key, arabic)
            if (source.length <= fuzzyMaxChars) {
                val shape = shapeOf(key)
                if (shape.isNotEmpty()) fuzzy.put(shape, arabic)
            }
        }
    }

    fun remove(source: String) {
        val key = key(source)
        synchronized(lock) {
            exact.remove(key)
            if (source.length <= fuzzyMaxChars) fuzzy.remove(shapeOf(key))
        }
    }

    fun size(): Int = synchronized(lock) { exact.size() }
    fun fuzzySize(): Int = synchronized(lock) { fuzzy.size() }

    fun clear() = synchronized(lock) {
        exact.clear()
        fuzzy.clear()
    }

    /**
     * Shape of a short string: lower-cased, letters and digits only, vowels and repeated
     * letters collapsed. "KRAK!!", "Kraak" and "krak" share one shape; "You're going." and
     * "youre going" share one.
     */
    fun shapeOf(key: String): String {
        val sb = StringBuilder(key.length)
        var last = ' '
        for (c in key.lowercase()) {
            if (!c.isLetterOrDigit()) continue
            if (c in VOWELS) continue
            if (c == last) continue
            sb.append(c)
            last = c
        }
        return sb.toString()
    }

    companion object {
        private const val VOWELS = "aeiouy\u0627\u0648\u064A\u0649\u0629"

        /** Normalized exact key: trimmed, single-spaced, case-folded. */
        fun key(source: String): String =
            source.trim().replace(Regex("\\s+"), " ").lowercase()
    }
}
