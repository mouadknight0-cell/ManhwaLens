package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P0/C2 — regressions for the on-device English echo.
 *
 * The defect had four doors: ML Kit substituted the source when a block had no answer, its
 * progressive callback painted straight onto the overlay, the cache decorator skipped the
 * quality guard for `ml_kit_en_ar`, and the painter only refused *blank* text. These tests pin
 * the rules on both sides of the engine boundary:
 *  - [MlKitPageAssembly] (pure, JVM-testable) — assembly + progressive gateway;
 *  - [CachingTranslator] — fresh and cached ML Kit output, and the three cache tiers;
 *  - [ArabicValidator] — the shared verdicts themselves.
 *
 * `MlKitTranslator` itself needs Android (ML Kit + `android.util.Log`), so it is verified through
 * the pure helper it now delegates to; the engine keeps only batching and threading.
 */
class MlKitEnglishEchoTest {

    /** On-device engine stand-in: echoes the source for [echoIds], answers Arabic for the rest. */
    private class EchoOnDeviceEngine(private val echoIds: Set<Int>) : Translator {
        override val engineId = "ml_kit_en_ar"
        var calls = 0
            private set

        override fun translatePage(blocks: List<TranslationBlock>): PageTranslation {
            calls++
            return PageTranslation.Success(
                blocks = blocks.map { b ->
                    TranslatedBlock(b.id, b.english, if (b.id in echoIds) b.english else "مرحبا بك")
                },
                engineId = engineId,
                elapsedMs = 1,
                cacheHits = 0,
                cacheMisses = 0
            )
        }
    }

    /** Fixed-output stand-in for a remote engine, so its guard behaviour can be compared. */
    private class FixedEngine(
        override val engineId: String,
        private val output: (TranslationBlock) -> String
    ) : Translator {
        override fun translatePage(blocks: List<TranslationBlock>): PageTranslation =
            PageTranslation.Success(
                blocks = blocks.map { TranslatedBlock(it.id, it.english, output(it)) },
                engineId = engineId,
                elapsedMs = 1,
                cacheHits = 0,
                cacheMisses = 0
            )
    }

    // ── the verdicts ML Kit output is now held to ───────────────────────────────────────────

    /** plain English dialogue must never count as a translation. */
    @Test
    fun englishEchoIsRejectedByTheSharedGuard() {
        assertEquals(
            ArabicValidator.Issue.UNTRANSLATED_ECHO,
            ArabicValidator.issue("Where are you going?", "Where are you going?")
        )
    }

    /** Latin-only output is rejected, but a single preserved character name still passes. */
    @Test
    fun outputWithoutArabicIsRejectedUnlessItIsALegitimateStandaloneName() {
        assertNotNull(ArabicValidator.issue("I will wait here.", "I will wait here."))
        assertNull(ArabicValidator.issue("Hanwoo", "Hanwoo"))
    }

    /** Arabic dialogue that keeps a Latin character name stays usable. */
    @Test
    fun arabicDialogueWithALatinCharacterNameIsAccepted() {
        assertNull(ArabicValidator.issue("Wait, Wei Wuxian!", "انتظر يا Wei Wuxian!"))
    }

    /** Arabic followed by an untranslated English sentence is a partial translation. */
    @Test
    fun aPartiallyTranslatedSentenceWithAnEnglishTailIsRejected() {
        assertEquals(
            ArabicValidator.Issue.PARTIAL_TRANSLATION,
            ArabicValidator.issue(
                "Why did you come back to this place again?",
                "لماذا عدت إلى come back to this place again?"
            )
        )
    }

    // ── page assembly: a missing answer is empty, never English ─────────────────────────────

    /** A block ML Kit answered nothing for stays empty; ids and page order are untouched. */
    @Test
    fun aBlockWithNoMlKitAnswerStaysEmptyAndKeepsPageOrder() {
        val blocks = listOf(
            TranslationBlock(7, "Where are you going?"),
            TranslationBlock(8, "   "),
            TranslationBlock(9, "Hanwoo")
        )
        // Only the third block got an answer.
        val ordered = MlKitPageAssembly.ordered(blocks, mapOf(9 to "هان وو"))

        assertEquals(listOf(7, 8, 9), ordered.map { it.id })
        assertEquals("", ordered[0].arabic)
        assertEquals("", ordered[1].arabic)
        assertEquals("هان وو", ordered[2].arabic)
        // The source is still carried: the painter/fallback needs it, but it is never the output.
        assertEquals("Where are you going?", ordered[0].english)
        assertTrue(ordered.none { it.arabic.isNotBlank() && it.arabic == it.english })
    }

    // ── caches: rejected output is never stored, stale output never wins ────────────────────

    /** An echo is not written to memory, disk or the fuzzy memo; the sibling block still is. */
    @Test
    fun rejectedMlKitOutputIsNotWrittenToMemoryDiskOrFuzzyCaches() {
        val file = File.createTempFile("mlkit-echo", ".json").also { it.deleteOnExit() }
        val disk = PersistentTranslationCache(file)
        val memo = TranslationMemo()
        val cache = TranslationCache()
        val engine = EchoOnDeviceEngine(echoIds = setOf(1))
        val caching = CachingTranslator(engine, cache, disk, memo = memo)

        val page = caching.translatePage(
            listOf(TranslationBlock(1, "Where are you going?"), TranslationBlock(2, "Come back"))
        ) as PageTranslation.Success

        assertEquals("", page.blocks.first { it.id == 1 }.arabic)
        assertTrue(page.blocks.first { it.id == 2 }.arabic.isNotBlank())
        assertNull(cache.get("ml_kit_en_ar", "Where are you going?"))
        assertNull(disk.get(cache.key("ml_kit_en_ar", "Where are you going?")))
        assertNull(memo.get("Where are you going?"))
        assertNull(memo.get("where are you going?"))
        assertNotNull(disk.get(cache.key("ml_kit_en_ar", "Come back")))

        // Nothing was memoised, so the same echo must go back to the engine (no free "hit").
        assertEquals(1, engine.calls)
        caching.translatePage(listOf(TranslationBlock(1, "Where are you going?")))
        assertEquals(2, engine.calls)
    }

    /** A pre-guard ML Kit entry is a cache miss: it is evicted and the block is re-translated. */
    @Test
    fun invalidCachedMlKitOutputIsDiscardedAndRetranslated() {
        val cache = TranslationCache()
        cache.put("ml_kit_en_ar", "Where are you going?", "Where are you going?")
        val engine = EchoOnDeviceEngine(echoIds = emptySet())
        val caching = CachingTranslator(engine, cache)

        val page = caching.translatePage(listOf(TranslationBlock(1, "Where are you going?")))
            as PageTranslation.Success

        val arabic = page.blocks.single().arabic
        assertTrue(arabic.isNotBlank())
        assertNotEquals("Where are you going?", arabic)
        assertEquals(1, engine.calls)
        assertEquals(arabic, cache.get("ml_kit_en_ar", "Where are you going?"))
    }

    // ── the progressive path: withheld when bad, instant when good ──────────────────────────

    /** Nothing the guard rejects may reach the painter through the progressive callback. */
    @Test
    fun theProgressiveGatewayNeverDeliversRejectedOutput() {
        val delivered = ArrayList<TranslatedBlock>()
        val paint = { tb: TranslatedBlock -> delivered += tb }

        val candidates = listOf(
            TranslatedBlock(1, "Where are you going?", "Where are you going?"),
            TranslatedBlock(2, "Come back", ""),
            TranslatedBlock(
                3,
                "Why did you come back to this place again?",
                "لماذا عدت إلى come back to this place again?"
            )
        )
        for (candidate in candidates) {
            MlKitPageAssembly.progressivePayload(candidate)?.let(paint)
        }

        assertTrue(delivered.isEmpty())
    }

    /** Valid Arabic is handed to the painter immediately and unchanged (progressive stays). */
    @Test
    fun validArabicStillReachesThePainterImmediately() {
        val dialogue = TranslatedBlock(4, "Wait, Wei Wuxian!", "انتظر يا Wei Wuxian!")
        assertSame(dialogue, MlKitPageAssembly.progressivePayload(dialogue))

        // A legitimately preserved standalone name is not withheld either.
        val name = TranslatedBlock(5, "Hanwoo", "Hanwoo")
        assertSame(name, MlKitPageAssembly.progressivePayload(name))

        val delivered = ArrayList<TranslatedBlock>()
        MlKitPageAssembly.progressivePayload(dialogue)?.let { delivered += it }
        assertEquals(1, delivered.size)
        assertSame(dialogue, delivered.single())
    }

    // ── remote engines keep exactly the behaviour they had ──────────────────────────────────

    /** Gemini and LibreTranslate were already guarded; the rule must not shift for them. */
    @Test
    fun geminiAndLibreTranslateGuardBehaviourIsUnchanged() {
        for (engineId in listOf("gemini", "libre_translate")) {
            val cache = TranslationCache()
            val engine = FixedEngine(engineId) { b ->
                if (b.id == 1) b.english else "مرحبا بك"
            }
            val caching = CachingTranslator(engine, cache)

            val page = caching.translatePage(
                listOf(TranslationBlock(1, "Where are you going?"), TranslationBlock(2, "Come back"))
            ) as PageTranslation.Success

            assertEquals("", page.blocks.first { it.id == 1 }.arabic)
            assertTrue(page.blocks.first { it.id == 2 }.arabic.isNotBlank())
            assertNull(cache.get(engineId, "Where are you going?"))
            assertNotNull(cache.get(engineId, "Come back"))
        }
    }
}
