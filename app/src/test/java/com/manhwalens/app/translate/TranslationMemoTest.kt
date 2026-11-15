package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fuzzy translation memory (v0.11.0). The point is SPEED: a Live frame that re-reads a
 * nearly identical short line must not pay an engine round-trip.
 */
class TranslationMemoTest {

    @Test
    fun exactHitReturnsTheStoredTranslation() {
        val memo = TranslationMemo()
        memo.put("Hello there", "مرحبا")
        val hit = memo.get("Hello there")
        assertNotNull(hit)
        assertTrue(hit!!.exact)
        assertEquals("مرحبا", hit.arabic)
    }

    @Test
    fun shortStringWithADifferentSpellingStillHits() {
        val memo = TranslationMemo()
        memo.put("KRAK!!", "كراك")
        // OCR re-reads the same burst with a different case/length/punctuation
        for (variant in listOf("Kraak", "krak", "KRAK!", "KRAK!!")) {
            val hit = memo.get(variant)
            assertNotNull("variant $variant should hit", hit)
            assertEquals("كراك", hit!!.arabic)
        }
    }

    @Test
    fun ambiguousShortDialogueNeverFuzzyMatchesDifferentWords() {
        val memo = TranslationMemo()
        memo.put("AH...", "آه...")
        assertNull("YEAH must not reuse AH's one-letter consonant shape", memo.get("YEAH"))

        memo.put("NO.", "لا.")
        assertNull("RUN must not reuse a two-letter consonant shape", memo.get("RUN"))
        memo.put("SORRY.", "آسف.")
        assertNull("short dialogue with an ambiguous shape must miss", memo.get("AH"))
    }

    @Test
    fun cachingTranslatorDoesNotCrossServeAhAndYeah() {
        val fake = FakeTranslator()
        val memo = TranslationMemo()
        val caching = CachingTranslator(fake, TranslationCache(), memo = memo)

        val ah = caching.translatePage(listOf(TranslationBlock(1, "AH..."))) as PageTranslation.Success
        val yeah = caching.translatePage(listOf(TranslationBlock(1, "YEAH"))) as PageTranslation.Success

        assertEquals("AR:AH...", ah.blocks.single().arabic)
        assertEquals("AR:YEAH", yeah.blocks.single().arabic)
        assertEquals("different short dialogue must reach the engine", 2, fake.calls)
    }

    @Test
    fun longSentencesNeverFuzzyMatch() {
        val memo = TranslationMemo()
        memo.put("You're going to keep coming from now on, okay? Except on weekends.", "ستواصل القدوم من الآن فصاعدًا.")
        // Same shape, different meaning → must NOT be served from the fuzzy tier.
        val hit = memo.get("You're going to keep coming from now on. Okay? Except on weekends!!")
        // Either a miss, or (only if it normalizes identically) an exact hit — never fuzzy.
        assertTrue(hit == null || hit.exact)
    }

    @Test
    fun blankTranslationsAreNeverStored() {
        val memo = TranslationMemo()
        memo.put("Hello", "   ")
        assertNull(memo.get("Hello"))
        assertEquals(0, memo.size())
    }

    @Test
    fun capacityIsBoundedPerTier() {
        val memo = TranslationMemo(exactCapacity = 4, fuzzyCapacity = 2, fuzzyMaxChars = 40)
        (1..10).forEach { memo.put("line $it", "سطر $it") }
        assertEquals(4, memo.size())
        assertTrue(memo.fuzzySize() <= 2)
    }

    @Test
    fun keysAreWhitespaceAndCaseInsensitive() {
        val memo = TranslationMemo()
        memo.put("  What   is   this?  ", "ما هذا؟")
        assertEquals("ما هذا؟", memo.get("what is this?")?.arabic)
    }

    @Test
    fun shapeOfCollapsesVowelsAndRepeats() {
        val memo = TranslationMemo()
        // Doubled letters and vowels collapse; the consonant skeleton is what is compared.
        val krak = memo.shapeOf("krak")
        assertEquals(krak, memo.shapeOf("kraak"))
        assertEquals(krak, memo.shapeOf("KRAK!"))
        assertEquals(memo.shapeOf("thud"), memo.shapeOf("thuud"))
        // Two different words keep different shapes.
        assertTrue(memo.shapeOf("krak") != memo.shapeOf("thud"))
    }

    @Test
    fun cachingTranslatorUsesTheMemoItIsGiven() {
        val memo = TranslationMemo()
        var calls = 0
        val engine = object : Translator {
            override val engineId = "fake_engine"
            override fun translatePage(blocks: List<TranslationBlock>): PageTranslation {
                calls++
                return PageTranslation.Success(
                    blocks.map { TranslatedBlock(it.id, it.english, "AR:${it.english}") },
                    engineId, 1, 0, 0
                )
            }
        }
        val cache = TranslationCache()
        val caching = CachingTranslator(engine, cache, memo = memo)
        caching.translatePage(listOf(TranslationBlock(1, "KRAK!")))
        assertEquals(1, calls)
        // Same burst, different OCR spelling: served from the memo, no second engine call.
        val second = caching.translatePage(listOf(TranslationBlock(1, "Kraak"))) as PageTranslation.Success
        assertEquals(1, calls)
        assertEquals("طَق!", second.blocks.single().arabic)
        // A fuzzy hit must never become an exact cache entry for different source text.
        assertNull(cache.get("fake_engine", "Kraak"))
        val third = caching.translatePage(listOf(TranslationBlock(1, "Kraak"))) as PageTranslation.Success
        assertEquals("طَق!", third.blocks.single().arabic)
        assertEquals(1, calls)
    }

    @Test
    fun eachEngineNamespaceHasItsOwnMemo() {
        val a = CachingTranslator.memoFor("ml_kit_en_ar", "default")
        val b = CachingTranslator.memoFor("gemini", "default")
        assertTrue(a !== b)
        assertEquals(a, CachingTranslator.memoFor("ml_kit_en_ar", "default"))
    }
}
