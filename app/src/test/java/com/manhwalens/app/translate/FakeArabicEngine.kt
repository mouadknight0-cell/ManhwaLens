package com.manhwalens.app.translate

/**
 * Test double for the on-device engine: every Latin word becomes an Arabic word of similar
 * length; digits, punctuation, whitespace and separators pass through untouched (a model
 * that RESPECTS separators). Wrap it to simulate models that mangle them.
 */
object FakeArabicEngine {
    private val WORD = Regex("[A-Za-z']+")
    private const val LETTERS = "ابتثجحخدذرزسشصضطظعغفقكلمنهوي"

    val translate: (String) -> String = { input ->
        WORD.replace(input) { m -> m.value.indices.joinToString("") { i -> LETTERS[(m.value[i].code + i) % LETTERS.length].toString() } }
    }
}

/** In-memory [BatchStore]. */
class MapBatchStore : BatchStore {
    val strings = HashMap<String, String>()
    val longs = HashMap<String, Long>()
    override fun getString(key: String, def: String): String = strings[key] ?: def
    override fun putString(key: String, value: String) { strings[key] = value }
    override fun getLong(key: String, def: Long): Long = longs[key] ?: def
    override fun putLong(key: String, value: Long) { longs[key] = value }
}
