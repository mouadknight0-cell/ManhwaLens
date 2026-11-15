package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class BlockPriorityTest {

    @Test
    fun shortBubblesFirst() {
        val texts = listOf("a long sentence that definitely exceeds eighty characters in total length yes indeed it really does exceed it a lot", "KRAK", "ALRIGHT, I'LL HAVE A GLASS.")
        val order = BlockPriority.order(texts, listOf(9000f, 100f, 3000f), listOf(10f, 500f, 100f))
        // Both "KRAK" and "ALRIGHT..." (<80 chars) beat the long one; KRAK is largest? no — ALRIGHT has area 3000 vs KRAK 100
        assertEquals(listOf(2, 1, 0), order)
    }

    @Test
    fun withinShort_largestAreaFirst() {
        val texts = listOf("tiny text", "medium text!", "big text here?")
        val order = BlockPriority.order(texts, listOf(10f, 500f, 9000f), listOf(0f, 0f, 0f))
        assertEquals(listOf(2, 1, 0), order)
    }

    @Test
    fun withinShortAndArea_mostCentralFirst() {
        val texts = listOf("one", "two", "three")
        val order = BlockPriority.order(texts, listOf(100f, 100f, 100f), listOf(300f, 10f, 150f))
        assertEquals(listOf(1, 2, 0), order)
    }

    @Test
    fun isShortBoundary() {
        assertEquals(true, BlockPriority.isShort("x".repeat(79)))
        assertEquals(false, BlockPriority.isShort("x".repeat(80)))
        assertEquals(false, BlockPriority.isShort("x".repeat(200)))
    }

    @Test
    fun rectOrdering_centralBubbleBeatsCornberOne() {
        // two identical-size short bubbles; one near frame center, one in a corner
        val texts = listOf("center bubble", "corner bubble")
        val order = BlockPriority.orderRects(
            texts,
            left = listOf(700f, 10f), top = listOf(780f, 10f),
            right = listOf(900f, 210f), bottom = listOf(880f, 110f),
            frameWidth = 1600, frameHeight = 1600
        )
        assertEquals(0, order.first())
    }
}
