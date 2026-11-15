package com.manhwalens.app.render

import org.junit.Assert.assertEquals
import org.junit.Test

class LargestBoxesTest {

    private fun box(id: Int, w: Int, h: Int) = BubbleRenderBox(
        id = id, left = 0, top = 0, right = w, bottom = h,
        english = "e", arabic = "ع", backgroundColor = -1, textColor = -16777216
    )

    @Test
    fun select_picksThreeLargestByArea() {
        val boxes = listOf(box(1, 10, 10), box(2, 100, 100), box(3, 30, 30), box(4, 50, 50), box(5, 20, 20))
        assertEquals(listOf(2, 4, 3), LargestBoxes.select(boxes, 3).map { it.id })
    }

    @Test
    fun select_fewerThanLimitKeepsAll() {
        val boxes = listOf(box(1, 10, 10), box(2, 20, 20))
        assertEquals(listOf(2, 1), LargestBoxes.select(boxes, 3).map { it.id })
    }

    @Test
    fun select_zeroOrNegativeLimitIsEmpty() {
        val boxes = listOf(box(1, 10, 10))
        assertEquals(0, LargestBoxes.select(boxes, 0).size)
    }

    @Test
    fun areaOf_degenerateRectIsZero() {
        assertEquals(0L, LargestBoxes.areaOf(box(1, 0, 50)))
    }
}
