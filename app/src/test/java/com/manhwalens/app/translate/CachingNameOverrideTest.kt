package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 — a name correction must beat the cache.
 *
 * A reader who fixes "هان جي وو" → "هان جيو" expects the next capture to show the new spelling,
 * including for a page that was translated last week. These tests pin the two places
 * [CachingTranslator.pinnedOverride] is applied: a cache hit (before the quality guard judges it)
 * and a fresh engine reply (before it is cached again).
 */
class CachingNameOverrideTest {

    private val english = "Han Ji-woo left."

    /** Every spelling of the character maps to the corrected one; anything else is untouched. */
    private val correct: (String, String) -> String = { source, arabic ->
        arabic.replace("هان جي وو", "هان جيو").replace("هان جيوو", "هان جيو")
    }

    @Test
    fun `a cache hit is repaired before it is shown`() {
        val cache = TranslationCache()
        cache.put(ENGINE, english, "هان جي وو غادر.")
        val delegate = RecordingEngine("never used")
        val caching = CachingTranslator(delegate, cache, pinnedOverride = correct)

        val result = caching.translatePage(page()) as PageTranslation.Success

        assertEquals("هان جيو غادر.", result.blocks.single().arabic)
        assertEquals(0, delegate.calls)
    }

    @Test
    fun `the repair is written back so the next capture is already correct`() {
        val cache = TranslationCache()
        cache.put(ENGINE, english, "هان جي وو غادر.")
        val caching = CachingTranslator(RecordingEngine("unused"), cache, pinnedOverride = correct)

        caching.translatePage(page())
        assertEquals("هان جيو غادر.", cache.get(ENGINE, english))
    }

    @Test
    fun `fresh engine output is repaired before it is cached`() {
        val cache = TranslationCache()
        val delegate = RecordingEngine("هان جي وو غادر.")
        val caching = CachingTranslator(delegate, cache, pinnedOverride = correct)

        val result = caching.translatePage(page()) as PageTranslation.Success

        // The shared post-processing pass runs on fresh engine output, so assert on the name
        // rather than on punctuation the polish stage is allowed to adjust.
        assertTrue(result.blocks.single().arabic.contains("هان جيو"))
        assertEquals(1, delegate.calls)
        assertTrue(cache.get(ENGINE, english)!!.contains("هان جيو"))
    }

    @Test
    fun `without an override the cached text is served unchanged`() {
        val cache = TranslationCache()
        cache.put(ENGINE, english, "هان جي وو غادر.")
        val caching = CachingTranslator(RecordingEngine("unused"), cache)

        val result = caching.translatePage(page()) as PageTranslation.Success

        assertEquals("هان جي وو غادر.", result.blocks.single().arabic)
    }

    @Test
    fun `a throwing override can never break a translation`() {
        val cache = TranslationCache()
        cache.put(ENGINE, english, "هان جي وو غادر.")
        val caching = CachingTranslator(
            RecordingEngine("unused"),
            cache,
            pinnedOverride = { _, _ -> error("boom") }
        )

        val result = caching.translatePage(page()) as PageTranslation.Success

        assertEquals("هان جي وو غادر.", result.blocks.single().arabic)
        assertTrue(result.blocks.single().arabic.isNotBlank())
    }

    private fun page() = listOf(TranslationBlock(id = 1, english = english))

    /** Minimal engine: returns one block and counts how often it was asked. */
    private class RecordingEngine(private val arabic: String) : Translator {
        var calls = 0
            private set

        override val engineId = ENGINE

        override fun translatePage(blocks: List<TranslationBlock>): PageTranslation {
            calls++
            return PageTranslation.Success(
                blocks = blocks.map { TranslatedBlock(it.id, it.english, arabic) },
                engineId = engineId,
                elapsedMs = 1,
                cacheHits = 0,
                cacheMisses = blocks.size
            )
        }
    }

    private companion object {
        const val ENGINE = "fake_offline"
    }
}
