package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CancellationException

/**
 * P2-06 — a failed bubble must never cost the page.
 *
 * Before this rule existed `MlKitTranslator.translateParallel` returned null as soon as **one**
 * bubble failed, so every answer that had already arrived was discarded and the whole page was
 * re-translated from scratch; if that bubble failed again the page was reported as a failure and
 * the reader got no Arabic at all — on the default, offline engine. Long bubbles had the harsher
 * version: one failed chunk aborted the entire page before any recovery was attempted.
 *
 * The decisions now live in [MlKitPageAssembly] (pure, JVM-testable) while `MlKitTranslator`
 * keeps only batching, timeouts and threading:
 *  - [MlKitPageAssembly.assemble] — page order, empty bubbles, "is this page a failure?";
 *  - [MlKitPageAssembly.retryMissing] — retry the unanswered ids only, never the answered ones;
 *  - [MlKitPageAssembly.joinChunks] — a long bubble is complete or it is empty.
 */
class MlKitFailurePolicyTest {

    private fun page(vararg english: String): List<TranslationBlock> =
        english.mapIndexed { index, text -> TranslationBlock(index + 1, text) }

    // 1 ------------------------------------------------------------------------------------------

    @Test
    fun allAnsweredBubblesKeepOrderAndText() {
        val blocks = page("One.", "Two.", "Three.")

        val assembly = MlKitPageAssembly.assemble(
            blocks,
            mapOf(1 to "واحد", 2 to "اثنان", 3 to "ثلاثة")
        )

        assertEquals(listOf(1, 2, 3), assembly.blocks.map { it.id })
        assertEquals(listOf("واحد", "اثنان", "ثلاثة"), assembly.blocks.map { it.arabic })
        assertEquals(3, assembly.translated)
        assertTrue(assembly.unansweredIds.isEmpty())
        assertFalse(MlKitPageAssembly.mustFail(assembly, blocks.size))
    }

    // 2 ------------------------------------------------------------------------------------------

    @Test
    fun oneFailedBubbleKeepsTheOtherResults() {
        val blocks = page("One.", "Two.", "Three.")

        // Bubble #2 never came back — the other two did, and they must survive.
        val assembly = MlKitPageAssembly.assemble(blocks, mapOf(1 to "واحد", 3 to "ثلاثة"))

        assertEquals(2, assembly.translated)
        assertEquals(listOf(2), assembly.unansweredIds)
        assertEquals("واحد", assembly.blocks[0].arabic)
        assertEquals("", assembly.blocks[1].arabic)
        assertEquals("ثلاثة", assembly.blocks[2].arabic)
        assertFalse(MlKitPageAssembly.mustFail(assembly, blocks.size))
    }

    // 3 ------------------------------------------------------------------------------------------

    @Test
    fun onlyTheUnansweredBubbleIsRetried() {
        val blocks = page("One.", "Two.", "Three.")
        val asked = mutableListOf<Int>()

        val merged = MlKitPageAssembly.retryMissing(
            blocks,
            mapOf(1 to "واحد", 3 to "ثلاثة")
        ) { block ->
            asked += block.id
            "اثنان"
        }

        // A bubble that already has an answer is never sent again.
        assertEquals(listOf(2), asked)
        assertEquals("اثنان", merged[2])
        assertEquals("واحد", merged[1])
        assertEquals("ثلاثة", merged[3])
    }

    // 4 ------------------------------------------------------------------------------------------

    @Test
    fun aFailedRetryLeavesThatBubbleEmptyAndKeepsTheRest() {
        val blocks = page("One.", "Two.", "Three.")

        val merged = MlKitPageAssembly.retryMissing(blocks, mapOf(1 to "واحد", 3 to "ثلاثة")) { null }
        val assembly = MlKitPageAssembly.assemble(blocks, merged)

        assertEquals(2, assembly.translated)
        assertEquals(listOf(2), assembly.unansweredIds)
        assertEquals("", assembly.blocks[1].arabic)
        assertFalse(MlKitPageAssembly.mustFail(assembly, blocks.size))
    }

    // 5 ------------------------------------------------------------------------------------------

    @Test
    fun severalFailedBubblesAreRetriedIndependently() {
        val blocks = page("One.", "Two.", "Three.", "Four.")
        val asked = mutableListOf<Int>()

        val merged = MlKitPageAssembly.retryMissing(
            blocks,
            mapOf(1 to "واحد", 3 to "ثلاثة")
        ) { block ->
            asked += block.id
            if (block.id == 4) "أربعة" else null
        }

        // Both missing ids are attempted once, in page order; one recovers, one does not.
        assertEquals(listOf(2, 4), asked)
        val assembly = MlKitPageAssembly.assemble(blocks, merged)
        assertEquals(listOf(2), assembly.unansweredIds)
        assertEquals("أربعة", assembly.blocks[3].arabic)
        assertEquals("", assembly.blocks[1].arabic)
    }

    // 6 ------------------------------------------------------------------------------------------

    @Test
    fun aPageWithNoUsableBubbleMustFail() {
        val blocks = page("One.", "Two.")

        val assembly = MlKitPageAssembly.assemble(blocks, emptyMap())

        assertEquals(0, assembly.translated)
        assertEquals(listOf(1, 2), assembly.unansweredIds)
        assertTrue(MlKitPageAssembly.mustFail(assembly, blocks.size))
        // An empty request (nothing to translate) is never a failure.
        assertFalse(MlKitPageAssembly.mustFail(assembly, 0))
    }

    @Test
    fun aPageWithOneUsableBubbleIsNotAFailure() {
        val blocks = page("One.", "Two.")

        val assembly = MlKitPageAssembly.assemble(blocks, mapOf(2 to "اثنان"))

        assertEquals(1, assembly.translated)
        assertFalse(MlKitPageAssembly.mustFail(assembly, blocks.size))
    }

    // 7 ------------------------------------------------------------------------------------------

    @Test
    fun aLongBubbleJoinsAllItsChunks() {
        assertEquals("a b c", MlKitPageAssembly.joinChunks(listOf("a", "b", "c")))
        assertEquals("واحد اثنان", MlKitPageAssembly.joinChunks(listOf(" واحد ", "اثنان")))
    }

    // 8 ------------------------------------------------------------------------------------------

    @Test
    fun aLongBubbleWithAFailedChunkIsNeverPresentedPartially() {
        // 2 of 3 chunks arrived: that is NOT a translation of the bubble — it would silently
        // lose the ending — so the bubble is empty instead of a plausible-looking fragment.
        assertNull(MlKitPageAssembly.joinChunks(listOf("a", null, "c")))
        assertNull(MlKitPageAssembly.joinChunks(listOf("a", "   ", "c")))
        assertNull(MlKitPageAssembly.joinChunks(emptyList()))

        val blocks = page("A long narration that had to be chunked.")
        val assembly = MlKitPageAssembly.assemble(blocks, emptyMap())
        assertEquals("", assembly.blocks.single().arabic)
    }

    // 9 ------------------------------------------------------------------------------------------

    @Test
    fun cancellationPropagatesInsteadOfBecomingATranslationFailure() {
        val blocks = page("One.", "Two.")

        // The engine call throws CancellationException: retryMissing must not swallow it and
        // must not turn it into "this bubble has no answer".
        assertThrows(CancellationException::class.java) {
            MlKitPageAssembly.retryMissing(blocks, emptyMap()) {
                throw CancellationException("pipeline cancelled")
            }
        }
    }

    // 10 -----------------------------------------------------------------------------------------

    @Test
    fun aMissingBubbleStaysEmptyAndNeverCarriesTheEnglishSource() {
        val blocks = page("Where are you going?")

        val assembly = MlKitPageAssembly.assemble(blocks, emptyMap())
        val only = assembly.blocks.single()

        assertEquals("", only.arabic)                       // nothing painted
        assertEquals("Where are you going?", only.english)  // source kept for the painter, never as output
    }

    // 11 -----------------------------------------------------------------------------------------

    @Test
    fun assemblyKeepsPageOrderWhenAnswersArriveOutOfOrder() {
        val blocks = page("One.", "Two.", "Three.")

        // The parallel pass completes in whatever order the engine finishes.
        val assembly = MlKitPageAssembly.assemble(
            blocks,
            mapOf(3 to "ثلاثة", 1 to "واحد", 2 to "اثنان")
        )

        assertEquals(listOf(1, 2, 3), assembly.blocks.map { it.id })
        assertEquals(listOf("واحد", "اثنان", "ثلاثة"), assembly.blocks.map { it.arabic })
    }
}
