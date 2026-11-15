package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageBatchTest {

    @Test
    fun joinTrimsAndSeparatorJoins() {
        val joined = PageBatch.join(
            listOf(
                TranslationBlock(1, "  Hello  "),
                TranslationBlock(2, "world\n")
            )
        )
        assertEquals("Hello\n@@@\nworld", joined)
    }

    @Test
    fun splitToleratesMessySeparatorSpacing() {
        val parts = PageBatch.split("فقاعة أولى \n\n@@@\nفقاعة ثانية", 2)
        assertEquals(listOf("فقاعة أولى", "فقاعة ثانية"), parts)
    }

    @Test
    fun splitMultiLineBlockContentSurvives() {
        // Model legitimately wraps a long line — internal newline must NOT split parts.
        val parts = PageBatch.split("سطر أول\nسطر ثانٍ من نفس الفقاعة\n@@@\nفقاعة ثانية", 2)
        assertEquals(2, parts?.size)
        assertEquals("سطر أول\nسطر ثانٍ من نفس الفقاعة", parts?.get(0))
    }

    @Test
    fun splitExtraBlocksReturnsNull() {
        assertNull(PageBatch.split("a\n@@@\nb\n@@@\nc", 2))
    }

    @Test
    fun splitExactMatchReturnsTrimmedParts() {
        val parts = PageBatch.split("واحد\n@@@\nاثنين\n@@@\nثلاثة", 3)
        assertEquals(listOf("واحد", "اثنين", "ثلاثة"), parts)
    }

    @Test
    fun splitLineCountMismatchReturnsNull() {
        assertNull(PageBatch.split("واحد\nاثنين", 3))
        assertNull(PageBatch.split("أ\nب\nج\nد", 3))
    }

    @Test
    fun joinThenSplitRoundtrips() {
        val blocks = listOf(
            TranslationBlock(7, "First bubble"),
            TranslationBlock(8, "Second bubble"),
            TranslationBlock(9, "KRAK!")
        )
        // Simulate an ideal model that echoes one translated line per input line.
        val fakeResponse = "فقاعة أولى\n@@@\nفقاعة ثانية\n@@@\nكراك"
        val parts = PageBatch.split(fakeResponse, blocks.size)
        assertEquals(listOf("فقاعة أولى", "فقاعة ثانية", "كراك"), parts)
    }
}
