package com.manhwalens.app.translate.bergamot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BergamotTextPreparationTest {

    @Test
    fun joinsOcrWrappedLinesWithSingleSpaces() {
        assertEquals(
            "I NEVER EXPECTED THIS TO HAPPEN",
            BergamotTextPreparation.asSingleLine("I NEVER EXPECTED\n THIS   TO\tHAPPEN")
        )
    }

    @Test
    fun healsHyphenatedWordSplitAtLineEdge() {
        assertEquals(
            "I will continue tomorrow",
            BergamotTextPreparation.asSingleLine("I will con-\n tinue tomorrow")
        )
    }

    @Test
    fun preservesOrdinaryInLineHyphen() {
        assertEquals(
            "Jin-Woo is here",
            BergamotTextPreparation.asSingleLine("Jin-Woo is here")
        )
    }

    @Test
    fun removesZeroWidthArtifactsWithoutAddingSpaces() {
        assertEquals(
            "continue",
            BergamotTextPreparation.asSingleLine("con\u200Btinue\uFEFF")
        )
    }

    @Test
    fun ordinaryPageUsesOneNativeBatch() {
        val texts = (1..20).map { "Bubble $it" }
        assertEquals(listOf(texts), BergamotTextPreparation.adaptiveBatches(texts))
    }

    @Test
    fun oversizedPageIsSplitWithoutReorderingOrDroppingTexts() {
        val texts = (1..60).map { "Bubble $it " + "x".repeat(220) }
        val batches = BergamotTextPreparation.adaptiveBatches(texts)
        assertTrue(batches.size > 1)
        assertEquals(texts, batches.flatten())
        assertTrue(batches.all { it.size <= 16 })
        assertTrue(batches.all { batch -> batch.sumOf { it.length } <= 8_000 })
    }

    @Test
    fun emptyInputProducesNoBatches() {
        assertTrue(BergamotTextPreparation.adaptiveBatches(emptyList()).isEmpty())
    }
}
