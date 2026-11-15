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
 *  2. **shape key** — the source with punctuation/case normalized and adjacent repeated
 *     letters collapsed. Vowels and word boundaries are preserved: deleting vowels made
 *     unrelated words such as "first" and "forest" share a shape.
 *     Only consulted for SHORT strings (≤ [fuzzyMaxChars]) with a distinctive canonical form.
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
            // Tiny or weak shapes are too ambiguous for dialogue. Keep fuzzy reuse
            // conservative: punctuation/case and repeated-letter variation are ignored,
            // but vowel identity and word boundaries remain significant.
            if (!isFuzzySafeShape(shape)) return null
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
                if (isFuzzySafeShape(shape)) fuzzy.put(shape, arabic)
            }
        }
    }

    fun remove(source: String) {
        val key = key(source)
        synchronized(lock) {
            exact.remove(key)
            if (source.length <= fuzzyMaxChars) {
                val shape = shapeOf(key)
                if (isFuzzySafeShape(shape)) fuzzy.remove(shape)
            }
        }
    }

    fun size(): Int = synchronized(lock) { exact.size() }
    fun fuzzySize(): Int = synchronized(lock) { fuzzy.size() }

    fun clear() = synchronized(lock) {
        exact.clear()
        fuzzy.clear()
    }

    /**
     * Conservative short-string canonicalization: lowercase, ignore punctuation,
     * collapse adjacent repeated letters, but preserve vowels and whitespace boundaries.
     * "KRAK!!", "Kraak" and "krak" share "krak"; "first" and "forest" do not collide.
     */
    fun shapeOf(key: String): String {
        val sb = StringBuilder(key.length)
        var last = ' '
        var pendingSpace = false
        for (c in key.lowercase()) {
            if (c.isLetterOrDigit()) {
                if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                pendingSpace = false
                if (c == last) continue
                sb.append(c)
                last = c
            } else {
                // Punctuation is ignored, while real whitespace remains a token boundary.
                if (c.isWhitespace()) {
                    pendingSpace = true
                    last = ' '
                }
            }
        }
        return sb.toString()
    }

    private fun isFuzzySafeShape(shape: String): Boolean {
        val compact = shape.filter { it.isLetterOrDigit() }
        return compact.length >= MIN_FUZZY_SHAPE_LENGTH &&
            compact.toSet().size >= MIN_FUZZY_DISTINCT_LETTERS
    }

    companion object {
        private const val MIN_FUZZY_SHAPE_LENGTH = 3
        private const val MIN_FUZZY_DISTINCT_LETTERS = 2

        /** Normalized exact key: trimmed, single-spaced, case-folded. */
        fun key(source: String): String =
            source.trim().replace(Regex("\\s+"), " ").lowercase()
    }
}
