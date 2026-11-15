package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityFallbackTranslatorTest {

    private class FakeTranslator(
        override val engineId: String,
        private val result: PageTranslation,
        private val received: MutableList<List<TranslationBlock>>
    ) : Translator {
        override fun translatePage(blocks: List<TranslationBlock>): PageTranslation {
            received += blocks
            return result
        }
    }

    private val page = listOf(
        TranslationBlock(1, "I will protect you."),
        TranslationBlock(2, "The gate is open."),
        TranslationBlock(3, "Run!")
    )

    @Test
    fun onlyBlankBergamotBubblesAreSentToOfflineFallback() {
        val primaryCalls = mutableListOf<List<TranslationBlock>>()
        val fallbackCalls = mutableListOf<List<TranslationBlock>>()
        val primary = FakeTranslator(
            "bergamot_en_ar",
            PageTranslation.Success(
                listOf(
                    TranslatedBlock(1, page[0].english, "سأحميك."),
                    TranslatedBlock(2, page[1].english, ""),
                    TranslatedBlock(3, page[2].english, "اركض!")
                ),
                "bergamot_en_ar", 300, 2, 1
            ),
            primaryCalls
        )
        val fallback = FakeTranslator(
            "ml_kit_en_ar",
            PageTranslation.Success(
                listOf(TranslatedBlock(2, page[1].english, "البوابة مفتوحة.")),
                "ml_kit_en_ar", 120, 0, 1
            ),
            fallbackCalls
        )

        val result = QualityFallbackTranslator(primary, fallback).translatePage(page) as PageTranslation.Success

        assertEquals(listOf(page), primaryCalls)
        assertEquals(listOf(listOf(page[1])), fallbackCalls)
        assertEquals(listOf("سأحميك.", "البوابة مفتوحة.", "اركض!"), result.blocks.map { it.arabic })
        assertEquals("bergamot_en_ar+ml_kit_fallback", result.engineId)
        assertEquals(420L, result.elapsedMs)
    }

    @Test
    fun acceptedBergamotPageDoesNotCallFallback() {
        val primaryCalls = mutableListOf<List<TranslationBlock>>()
        val fallbackCalls = mutableListOf<List<TranslationBlock>>()
        val primary = FakeTranslator(
            "bergamot_en_ar",
            PageTranslation.Success(
                page.map { TranslatedBlock(it.id, it.english, "ترجمة") },
                "bergamot_en_ar", 250, 0, page.size
            ),
            primaryCalls
        )
        val fallback = FakeTranslator(
            "ml_kit_en_ar",
            PageTranslation.Failure(FailureReason.UNKNOWN, "must not run", "ml_kit_en_ar"),
            fallbackCalls
        )

        val result = QualityFallbackTranslator(primary, fallback).translatePage(page) as PageTranslation.Success

        assertTrue(fallbackCalls.isEmpty())
        assertEquals("bergamot_en_ar", result.engineId)
        assertEquals(250L, result.elapsedMs)
    }

    @Test
    fun totalBergamotFailureFallsBackToTheWholePage() {
        val primary = FakeTranslator(
            "bergamot_en_ar",
            PageTranslation.Failure(FailureReason.UNKNOWN, "native failure", "bergamot_en_ar"),
            mutableListOf()
        )
        val fallback = FakeTranslator(
            "ml_kit_en_ar",
            PageTranslation.Success(
                page.map { TranslatedBlock(it.id, it.english, "ترجمة") },
                "ml_kit_en_ar", 500, 0, page.size
            ),
            mutableListOf()
        )

        val result = QualityFallbackTranslator(primary, fallback).translatePage(page) as PageTranslation.Success

        assertEquals(page.map { "ترجمة" }, result.blocks.map { it.arabic })
        assertEquals("bergamot_en_ar+ml_kit_fallback", result.engineId)
        assertEquals(500L, result.elapsedMs)
    }

    @Test
    fun fallbackFailureKeepsReadableBergamotResultsAndLeavesRejectedBubbleBlank() {
        val primary = FakeTranslator(
            "bergamot_en_ar",
            PageTranslation.Success(
                listOf(
                    TranslatedBlock(1, page[0].english, "سأحميك."),
                    TranslatedBlock(2, page[1].english, ""),
                    TranslatedBlock(3, page[2].english, "اركض!")
                ),
                "bergamot_en_ar", 300, 0, page.size
            ),
            mutableListOf()
        )
        val fallback = FakeTranslator(
            "ml_kit_en_ar",
            PageTranslation.Failure(FailureReason.UNKNOWN, "offline fallback failed", "ml_kit_en_ar"),
            mutableListOf()
        )

        val result = QualityFallbackTranslator(primary, fallback).translatePage(page) as PageTranslation.Success

        assertEquals(listOf("سأحميك.", "", "اركض!"), result.blocks.map { it.arabic })
        assertEquals("bergamot_en_ar", result.engineId)
    }
}
