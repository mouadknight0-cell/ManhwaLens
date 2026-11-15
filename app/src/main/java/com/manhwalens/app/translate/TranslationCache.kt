package com.manhwalens.app.translate

/**
 * One bounded cache for repeated source text (e.g. SFX like "KRAK", or
 * recurring in-joke lines across a series). In-memory LRU, process-scoped,
 * backed by [PersistentTranslationCache] (disk) in TranslatorFactory.
 *
 * Keys are namespaced by engine id + translation style and normalized
 * (trimmed + whitespace squashed) so trivial formatting differences still hit.
 */
class TranslationCache(private val maxEntries: Int = 1000) {

    private val map = object : LinkedHashMap<String, String>(16, 0.75f, /* accessOrder = */ true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > maxEntries
    }

    fun key(engineId: String, source: String, style: String = "default"): String =
        engineId + "|" + style + "|" + normalize(prepare(source))

    /** v0.9.14: names resolved + lines joined == what the engine is shown, so the key must
     *  use the same text ([Glossary.config], refreshed per engine build). */
    private fun prepare(source: String): String =
        runCatching { Glossary.cacheText(source) }.getOrDefault(source)

    @Synchronized
    fun get(engineId: String, source: String, style: String = "default"): String? = map[key(engineId, source, style)]

    @Synchronized
    fun put(engineId: String, source: String, translated: String, style: String = "default") {
        map[key(engineId, source, style)] = translated
    }

    @Synchronized
    fun remove(engineId: String, source: String, style: String = "default") {
        map.remove(key(engineId, source, style))
    }

    @Synchronized
    fun size(): Int = map.size

    @Synchronized
    fun clear() = map.clear()

    companion object {
        fun normalize(source: String): String =
            source.trim().replace(Regex("\\s+"), " ").lowercase()
    }
}
