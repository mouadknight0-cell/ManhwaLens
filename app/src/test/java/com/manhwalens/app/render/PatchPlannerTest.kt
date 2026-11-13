package com.manhwalens.app.render

import com.manhwalens.app.ocr.geometry.Rect2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.11.0: one bubble = one patch, decided from the whole page (never arrival order). */
class PatchPlannerTest {

    private val page = 800 to 1400

    @Test
    fun aSplitBubbleHasExactlyOneOwnerAndOneRender() {
        val areas = PatchPlanner.areas(
            listOf(
                4 to Rect2D(100f, 100f, 240f, 124f),
                5 to Rect2D(100f, 128f, 250f, 150f),
                6 to Rect2D(420f, 700f, 560f, 760f)
            ),
            page.first, page.second
        )
        assertEquals(2, areas.size)
        val merged = areas.first { it.merged }
        assertEquals(4, merged.ownerId)          // first in priority order owns
        assertEquals(listOf(4, 5), merged.memberIds)
        assertEquals(1, PatchPlanner.foldedCount(areas))
        // Only owners are rendered, and each area renders once.
        assertEquals(listOf(4, 6), PatchPlanner.renders(areas).map { it.first })
    }

    @Test
    fun theOwnerIsStableWhateverTheArrivalOrder() {
        val boxes = listOf(
            9 to Rect2D(100f, 100f, 240f, 124f),
            8 to Rect2D(100f, 128f, 250f, 150f)
        )
        assertEquals(9, PatchPlanner.ownerOf(PatchPlanner.areas(boxes, 800, 1400), 8))
        val reversed = boxes.reversed()
        assertEquals(8, PatchPlanner.ownerOf(PatchPlanner.areas(reversed, 800, 1400), 9))
    }

    @Test
    fun coverOfASiblingIsTheOwnersCover() {
        val areas = PatchPlanner.areas(
            listOf(
                1 to Rect2D(10f, 10f, 90f, 40f),
                2 to Rect2D(10f, 42f, 95f, 70f)
            ),
            300, 300
        )
        val ownerCover = PatchPlanner.coverFor(areas, 1)
        assertEquals(ownerCover, PatchPlanner.coverFor(areas, 2))
        assertTrue(ownerCover!!.bottom >= 70f)
    }

    @Test
    fun separateBubblesAreNotFolded() {
        val areas = PatchPlanner.areas(
            listOf(1 to Rect2D(10f, 10f, 90f, 40f), 2 to Rect2D(200f, 400f, 300f, 460f)),
            500, 900
        )
        assertEquals(0, PatchPlanner.foldedCount(areas))
        for (a in areas) assertFalse(a.merged)
    }

    @Test
    fun unknownIdAnswersItselfAndHasNoCover() {
        val areas = PatchPlanner.areas(listOf(1 to Rect2D(0f, 0f, 10f, 10f)), 50, 50)
        assertEquals(42, PatchPlanner.ownerOf(areas, 42))
        assertNull(PatchPlanner.coverFor(areas, 42))
    }

    @Test
    fun describeMentionsMergesOnlyWhenTheyHappen() {
        assertEquals("2 boxes → 2 patch area(s)", PatchPlanner.describe(
            2, PatchPlanner.areas(listOf(1 to Rect2D(0f, 0f, 10f, 10f), 2 to Rect2D(80f, 80f, 90f, 90f)), 200, 200)
        ))
        assertTrue(PatchPlanner.describe(
            2, PatchPlanner.areas(listOf(1 to Rect2D(0f, 0f, 10f, 10f), 2 to Rect2D(0f, 11f, 10f, 20f)), 200, 200)
        ).contains("1 merged"))
    }
}
