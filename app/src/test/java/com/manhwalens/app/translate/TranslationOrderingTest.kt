package com.manhwalens.app.translate

import com.manhwalens.app.translate.gemini.GeminiWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-02 — a page has two legitimate orders and they must not be confused.
 *
 *  - the LOCAL progressive draft paints in visual priority order (short, large, central first)
 *    so the bubbles that matter most appear first on a slow device;
 *  - every REMOTE request must be the page's reading order, because the remote prompt tells the
 *    model that consecutive blocks are consecutive dialogue and the chunker slices that list
 *    sequentially.
 *
 * Before this fix the relay handed `GeminiWire.chunkBlocks` the visual-priority list, so each
 * chunk was a random scattering of the page and the prompt's "consecutive" claim was false.
 * These tests pin both orders and the chunk boundaries between them.
 */
class TranslationOrderingTest {

    private fun b(id: Int, text: String) = TranslationBlock(id, text)

    /** 24 distinct, meaningful bubbles — enough for several chunks at a small budget. */
    private fun page(count: Int = 24): List<TranslationBlock> =
        (1..count).map { b(it, "I told you to wait at the gate for block $it of this page.") }

    private fun ids(blocks: List<TranslationBlock>) = blocks.map { it.id }

    /** Worst case for the old behaviour: the last bubble on the page paints first. */
    private fun reversedRanks(blocks: List<TranslationBlock>): Map<Int, Int> =
        blocks.withIndex().associate { (i, blk) -> blk.id to blocks.size - 1 - i }

    @Test
    fun remoteOrderIsPageOrder() {
        val blocks = page()
        val plan = PagePlan.prepare(blocks)
        assertEquals(24, plan.uniqueCount)
        // The order a remote engine receives: exactly the reading order of the page.
        assertEquals((1..24).toList(), ids(PagePlan.remoteOrder(plan)))
    }

    @Test
    fun visualPriorityOrderIsPreservedForTheLocalPass() {
        val blocks = page()
        val plan = PagePlan.prepare(blocks)
        val progressive = PagePlan.progressiveOrder(plan, reversedRanks(blocks))
        assertEquals((24 downTo 1).toList(), ids(progressive))
        // …and it really is a different order from the remote one.
        assertNotEquals(ids(PagePlan.remoteOrder(plan)), ids(progressive))
    }

    @Test
    fun unrankedBlocksGoLastInVisualPriorityOrder() {
        val blocks = page()
        val plan = PagePlan.prepare(blocks)
        // Only the last three bubbles are ranked (the others did not get a rank from the painter).
        val ranks = mapOf(24 to 0, 23 to 1, 22 to 2)
        assertEquals(
            listOf(24, 23, 22) + (1..21).toList(),
            ids(PagePlan.progressiveOrder(plan, ranks))
        )
    }

    @Test
    fun remoteChunksKeepThePageSequenceAcrossBoundaries() {
        val plan = PagePlan.prepare(page())
        val chunks = GeminiWire.chunkBlocks(PagePlan.remoteOrder(plan), maxChars = 200, maxBlocks = 8)
        assertTrue("must span several requests, was ${chunks.size}", chunks.size >= 3)
        // Every bubble is sent exactly once, and the flattened order is the page order.
        assertEquals((1..24).toList(), ids(chunks.flatten()))
        // No chunk straddles: each chunk is itself ascending, so the model sees dialogue in order.
        for (chunk in chunks) {
            assertEquals(ids(chunk).sorted(), ids(chunk))
            assertEquals(chunk.size, chunk.map { it.id }.distinct().size)
        }
    }

    @Test
    fun visualPriorityOrderWouldScrambleTheChunks() {
        // The regression this fix removes: the old relay chunked the priority list, so the
        // model received a scrambled page. Kept as a test so nobody re-introduces it.
        val blocks = page()
        val plan = PagePlan.prepare(blocks)
        val chunks = GeminiWire.chunkBlocks(
            PagePlan.progressiveOrder(plan, reversedRanks(blocks)),
            maxChars = 200,
            maxBlocks = 8
        )
        assertEquals((24 downTo 1).toList(), ids(chunks.flatten()))
        assertNotEquals((1..24).toList(), ids(chunks.flatten()))
    }

    @Test
    fun dedupeDoesNotDisturbTheRemoteOrder() {
        // The same string in two bubbles is sent once, under its FIRST id, and the rest of the
        // page keeps its order.
        val blocks = (1..21).map { b(it, "I told you to wait at the gate for block $it of this page.") }
            .toMutableList()
        blocks[16] = b(17, blocks[2].english) // id 17 repeats id 3's text
        val plan = PagePlan.prepare(blocks)
        assertEquals(20, plan.uniqueCount)
        assertEquals(21, plan.originalCount)
        val remote = PagePlan.remoteOrder(plan)
        assertEquals((1..21).filter { it != 17 }, ids(remote))
        assertEquals(ids(remote).sorted(), ids(remote))
        // The repeated string is answered once and fanned back out to both ids.
        val answers = remote.map { TranslatedBlock(it.id, it.english, "عربي-${it.id}") }
        val expanded = PagePlan.expand(plan, answers, blocks.associate { it.id to it.english })
        assertEquals("عربي-3", expanded.first { it.id == 3 }.arabic)
        assertEquals("عربي-3", expanded.first { it.id == 17 }.arabic)
    }

    @Test
    fun theLivePathStillUsesPageOrder() {
        // Live calls PagePlan.engineBlocks directly (FloatingButtonService, Live capture) and was
        // already page-ordered; remoteOrder must stay an alias of exactly that list.
        val plan = PagePlan.prepare(page())
        assertEquals(PagePlan.engineBlocks(plan), PagePlan.remoteOrder(plan))
        assertEquals((1..24).toList(), ids(PagePlan.engineBlocks(plan)))
    }
}
