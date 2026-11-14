package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinedFallbackTest {
    private fun t(s: SplitStrategy, ms: Long, ok: Boolean = true) = BatchAutoSelect.Trial(s, ok, ms, 0, "ok")

    @Test
    fun bulletIsPreferred_whenTimingsAreWithinNoise() {
        val ranked = BatchAutoSelect.rankCandidates(
            listOf(t(SplitStrategy.PIPE, 205), t(SplitStrategy.HASH, 230), t(SplitStrategy.BULLET, 214))
        )
        assertEquals(SplitStrategy.BULLET, ranked.first().strategy)
        assertEquals(SplitStrategy.PIPE, ranked[1].strategy)
    }

    @Test
    fun bulletIsNotPreferred_whenClearlySlower_orFailing() {
        val slow = BatchAutoSelect.rankCandidates(listOf(t(SplitStrategy.PIPE, 100), t(SplitStrategy.BULLET, 400)))
        assertEquals(SplitStrategy.PIPE, slow.first().strategy)
        val failing = BatchAutoSelect.rankCandidates(listOf(t(SplitStrategy.PIPE, 100), t(SplitStrategy.BULLET, 90, ok = false)))
        assertEquals(listOf(SplitStrategy.PIPE), failing.map { it.strategy })
    }

    @Test
    fun pageWhoseTextContainsTheMarker_isNotSentJoined() {
        assertFalse(BatchSplitter.sourcesSafe(SplitStrategy.PIPE, listOf("Hello", "A | B")))
        assertFalse(BatchSplitter.sourcesSafe(SplitStrategy.BULLET, listOf("• item", "x")))
        assertTrue(BatchSplitter.sourcesSafe(SplitStrategy.BULLET, listOf("Hello there", "A | B")))
        assertTrue(BatchSplitter.sourcesSafe(SplitStrategy.NEWLINE, listOf("anything")))
    }
}
