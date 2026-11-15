package com.manhwalens.app.render

import com.manhwalens.app.ocr.geometry.Rect2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.11.0: one opaque cover rect per visual area, dilated past the glyph pixels. */
class PatchCoverageTest {

    @Test
    fun oneBubbleSplitIntoTwoLinesBecomesOneCover() {
        // ML Kit grouped the two lines of a single bubble into two blocks (the smeared case).
        val groups = PatchCoverage.plan(
            listOf(
                1 to Rect2D(100f, 100f, 240f, 122f),
                2 to Rect2D(100f, 126f, 250f, 148f)
            ),
            frameW = 800, frameH = 1200
        )
        assertEquals(1, groups.size)
        assertEquals(listOf(1, 2), groups.single().memberIds)
        assertEquals(1, groups.single().ownerId) // first id draws the Arabic
        assertEquals(1, PatchCoverage.mergedGroups(groups).size)
    }

    @Test
    fun twoRealBubblesStaySeparate() {
        val groups = PatchCoverage.plan(
            listOf(
                1 to Rect2D(50f, 50f, 200f, 120f),
                2 to Rect2D(400f, 600f, 560f, 680f)
            ),
            frameW = 800, frameH = 1200
        )
        assertEquals(2, groups.size)
        assertEquals(listOf(1), groups.first { it.ownerId == 1 }.memberIds)
        assertEquals(emptyList<PatchCoverage.Group>(), PatchCoverage.mergedGroups(groups))
    }

    @Test
    fun coverGrowsPastTheOcrBoxSoNoGlyphSurvives() {
        val b = Rect2D(100f, 100f, 200f, 140f)
        val g = PatchCoverage.plan(listOf(7 to b), 800, 1200, dilatePx = 3f).single()
        assertTrue(g.rect.left < b.left)
        assertTrue(g.rect.top < b.top)
        assertTrue(g.rect.right > b.right)
        assertTrue(g.rect.bottom > b.bottom)
        assertEquals(3f, b.left - g.rect.left, 0.001f) // cover is LEFT of the box
    }

    @Test
    fun coverNeverLeavesTheFrame() {
        val g = PatchCoverage.plan(
            listOf(1 to Rect2D(0f, 0f, 40f, 30f), 2 to Rect2D(760f, 1180f, 800f, 1200f)),
            800, 1200
        )
        for (group in g) {
            assertTrue(group.rect.left >= 0f)
            assertTrue(group.rect.top >= 0f)
            assertTrue(group.rect.right <= 800f)
            assertTrue(group.rect.bottom <= 1200f)
        }
    }

    @Test
    fun coverOfAnswersForEveryMemberAndForNothingElse() {
        val groups = PatchCoverage.plan(
            listOf(3 to Rect2D(10f, 10f, 90f, 40f), 4 to Rect2D(12f, 42f, 95f, 70f)),
            500, 500
        )
        val cover = PatchCoverage.coverOf(groups, 3)
        assertNotNull(cover)
        assertEquals(cover, PatchCoverage.coverOf(groups, 4))
        assertNull(PatchCoverage.coverOf(groups, 99))
    }

    @Test
    fun emptyInputIsEmptyOutput() {
        assertEquals(emptyList<PatchCoverage.Group>(), PatchCoverage.plan(emptyList(), 100, 100))
    }
}
