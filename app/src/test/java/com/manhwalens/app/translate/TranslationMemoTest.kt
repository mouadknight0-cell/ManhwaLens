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
        assertNull("YEAH must not reuse AH's weak canonical shape", memo.get("YEAH"))

        memo.put("NO.", "لا.")
        assertNull("RUN must not reuse NO's weak canonical shape", memo.get("RUN"))
        memo.put("SORRY.", "آسف.")
        assertNull("short dialogue with an ambiguous shape must miss", memo.get("AH"))
    }

    @Test
    fun whitespaceDoesNotArtificiallyMakeAWeakShapeEligible() {
        val memo = TranslationMemo()
        memo.put("a b", "أ ب")
        assertEquals(0, memo.fuzzySize())
        assertNull(memo.get("a-b"))
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
    fun shapeOfCollapsesRepeatedLettersAndPunctuationButPreservesVowelsAndWordBoundaries() {
        val memo = TranslationMemo()
        val krak = memo.shapeOf("krak")
        assertEquals("krak", krak)
        assertEquals(krak, memo.shapeOf("kraak"))
        assertEquals(krak, memo.shapeOf("KRAK!!"))
        assertEquals(memo.shapeOf("thud"), memo.shapeOf("thuud"))
        assertEquals("youregoing", memo.shapeOf("You're going.").replace(" ", ""))
        assertTrue(memo.shapeOf("first") != memo.shapeOf("forest"))
        assertTrue(memo.shapeOf("a man") != memo.shapeOf("aman"))
        assertEquals("ah", memo.shapeOf("AH..."))
        assertEquals("yeah", memo.shapeOf("YEAH"))
    }

    @Test
    fun fuzzyCacheDoesNotCollideOnVowelOrWordBoundaryDifferences() {
        val memo = TranslationMemo()
        memo.put("first", "الأول")
        assertNull("forest must not receive the translation of first", memo.get("forest"))
        memo.put("a man", "رجل")
        assertNull("aman must not receive the translation of a man", memo.get("aman"))
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
        val traces = ArrayList<TranslationPairTrace>()
        val caching = CachingTranslator(engine, cache, memo = memo, onTranslationPair = { traces += it })
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
        assertEquals(listOf("OK", "FUZZY_HIT", "FUZZY_HIT"), traces.map { it.status })
        assertEquals(listOf("KRAK!", "Kraak", "Kraak"), traces.map { it.sourceEnglish })
    }

    @Test
    fun exactMemoHitIsRecognizedAndPromotedToTheExactCache() {
        val memo = TranslationMemo()
        val cache = TranslationCache()
        val fake = FakeTranslator()
        memo.put("Where are you?", "أين أنت؟")
        val caching = CachingTranslator(fake, cache, memo = memo)

        val result = caching.translatePage(listOf(TranslationBlock(1, "Where are you?"))) as PageTranslation.Success

        assertEquals("أين أنت؟", result.blocks.single().arabic)
        assertEquals(0, fake.calls)
        assertEquals("أين أنت؟", cache.get("fake_engine", "Where are you?"))
    }

    @Test
    fun eachEngineNamespaceHasItsOwnMemo() {
        val a = CachingTranslator.memoFor("ml_kit_en_ar", "default")
        val b = CachingTranslator.memoFor("gemini", "default")
        assertTrue(a !== b)
        assertEquals(a, CachingTranslator.memoFor("ml_kit_en_ar", "default"))
    }
}
