package com.manhwalens.app.translate

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.13.0 — Glossary.prepare() is memoized (it is a regex pass called several times
 * per block per page). The memo must be transparent: same input → same output, and
 * refresh() must invalidate it.
 */
class GlossaryMemoTest {

    @After
    fun reset() = Glossary.refresh("", true)

    @Test
    fun repeatedPrepareReturnsIdenticalResults() {
        Glossary.refresh("", true)
        val text = "Wei Wuxian, wait for Lan Wangji."
        val first = Glossary.prepare(text)
        repeat(5) { assertEquals(first, Glossary.prepare(text)) }
    }

    @Test
    fun refreshInvalidatesTheMemo() {
        Glossary.refresh("", true)
        val before = Glossary.prepare("Wei Wuxian and Hua Cheng")
        Glossary.refresh("Wei Wuxian = ويي ووشيان\nHua Cheng,هوا تشنغ", true)
        val after = Glossary.prepare("Wei Wuxian and Hua Cheng")
        assertNotEquals(before, after)
        assertTrue(after, after.contains("ويي ووشيان"))
        assertTrue(after, after.contains("هوا تشنغ"))
    }

    @Test
    fun mergeLinesFlagIsPartOfTheMemoKey() {
        Glossary.refresh("", true)
        val multiline = "Hello\nworld"
        val merged = Glossary.prepare(multiline, mergeLines = true)
        val unmerged = Glossary.prepare(multiline, mergeLines = false)
        assertNotEquals(merged, unmerged)
        // And each variant is stable across repeats.
        assertEquals(merged, Glossary.prepare(multiline, mergeLines = true))
        assertEquals(unmerged, Glossary.prepare(multiline, mergeLines = false))
    }

    @Test
    fun cacheTextIsStableAcrossCalls() {
        Glossary.refresh("", true)
        val text = "Lan Wangji smiled."
        assertEquals(Glossary.cacheText(text), Glossary.cacheText(text))
    }
}
