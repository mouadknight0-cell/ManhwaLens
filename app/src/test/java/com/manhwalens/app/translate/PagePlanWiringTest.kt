package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.11.0 wiring contract: [PagePlan] is what the pipeline calls before the engine, so the
 * meaningless blocks never cost a request and a missing answer is never painted as English.
 */
class PagePlanWiringTest {

    private fun block(id: Int, text: String) = TranslationBlock(id, text)

    @Test
    fun meaninglessBlocksNeverReachTheEngine() {
        val plan = PagePlan.prepare(
            listOf(
                block(1, "Where are you going?"),
                block(2, "......"),
                block(3, "Chapter 12"),
                block(4, "www.mangadex.org")
            )
        )
        assertEquals(1, plan.uniqueCount)
        assertEquals(3, plan.droppedCount)
        assertEquals(listOf(1), PagePlan.engineBlocks(plan).map { it.id })
        assertTrue(plan.dropped.map { it.second.name }.contains("PUNCTUATION_ONLY"))
    }

    @Test
    fun expansionRestoresEveryBubbleAndDropsTheMeaninglessOnes() {
        val blocks = listOf(
            block(1, "Who are you?"),
            block(2, "Who are you?"), // same string, second bubble
            block(3, "!!!")
        )
        val plan = PagePlan.prepare(blocks)
        val engine = PagePlan.engineBlocks(plan) // one block: "Who are you?"
        assertEquals(1, engine.size)
        val expanded = PagePlan.expand(
            plan,
            listOf(TranslatedBlock(engine.single().id, "Who are you?", "من أنت؟")),
            blocks.associate { it.id to it.english }
        )
        assertEquals(2, expanded.size)                                  // #3 dropped
        assertEquals("من أنت؟", expanded.first { it.id == 1 }.arabic)
        assertEquals("من أنت؟", expanded.first { it.id == 2 }.arabic)    // fanned out
        assertTrue(expanded.none { it.id == 3 })
    }

    @Test
    fun aRejectedBubbleIsEmptyRatherThanEnglish() {
        val plan = PagePlan.prepare(listOf(block(1, "Hello"), block(2, "What is this?")))
        val engine = PagePlan.engineBlocks(plan)
        // The engine echoed block #1 and answered block #2; the guard rejected the echo.
        val expanded = PagePlan.expand(
            plan,
            listOf(
                TranslatedBlock(engine[0].id, "Hello", ""),
                TranslatedBlock(engine[1].id, "What is this?", "ما هذا؟")
            ),
            mapOf(1 to "Hello", 2 to "What is this?")
        )
        assertEquals("", expanded.first { it.id == 1 }.arabic)
        assertEquals("ما هذا؟", expanded.first { it.id == 2 }.arabic)
    }

    @Test
    fun alreadyPaintedBubblesAreNotSentAgain() {
        val plan = PagePlan.prepare(listOf(block(1, "Wait!"), block(2, "Come back!")))
        val pending = PagePlan.pendingEngineBlocks(plan, known = mapOf(1 to "انتظر!"))
        assertEquals(1, pending.size)
        assertEquals("Come back!", pending.single().english)
    }

    @Test
    fun sanitizeKeepsWhatTheCallerWouldPaint() {
        val out = PagePlan.sanitizeEnglishEcho(
            listOf(TranslatedBlock(1, "Hi", ""), TranslatedBlock(2, "Bye", "وداعًا"))
        )
        assertEquals("", out.first { it.id == 1 }.arabic)
        assertEquals("وداعًا", out.first { it.id == 2 }.arabic)
        assertNull(PagePlan.arabicById(plan(1, "Hi"), listOf(TranslatedBlock(0, "Hi", "")))[1])
    }

    private fun plan(id: Int, text: String) = PagePlan.prepare(listOf(block(id, text)))
}
