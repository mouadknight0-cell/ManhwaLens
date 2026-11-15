package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class PageDedupeTest {

    private fun b(id: Int, text: String) = TranslationBlock(id, text)

    @Test
    fun identicalStringsAreCollapsedOnce() {
        val plan = PageDedupe.plan(listOf(b(1, "Hello"), b(2, "hello"), b(3, "World")))
        assertEquals(2, plan.unique.size)
        assertEquals(1, plan.removedCount)
    }

    @Test
    fun whitespaceVariantsCollapse() {
        val plan = PageDedupe.plan(listOf(b(1, "  Hello\n world "), b(2, "Hello   world")))
        assertEquals(1, plan.unique.size)
        assertEquals(2, plan.keyToIds["hello world"]!!.size)
    }

    @Test
    fun expandFansResultBackOut() {
        val original = listOf(b(1, "Hello"), b(2, "hello"), b(3, "Other"))
        val plan = PageDedupe.plan(original)
        val translated = plan.unique.map { TranslatedBlock(it.id, it.english, "مرحبا") }
        val expanded = PageDedupe.expand(plan, original, translated)
        assertEquals(3, expanded.size)
        assertEquals("مرحبا", expanded.first { it.id == 1 }.arabic)
        assertEquals("مرحبا", expanded.first { it.id == 2 }.arabic)
        assertEquals("مرحبا", expanded.first { it.id == 3 }.arabic)
    }

    @Test
    fun expandMissingTranslationYieldsEmpty() {
        val original = listOf(b(1, "Hello"))
        val plan = PageDedupe.plan(original)
        val expanded = PageDedupe.expand(plan, original, emptyList())
        assertEquals("", expanded.first().arabic)
    }

    @Test
    fun noDuplicatesPassthrough() {
        val plan = PageDedupe.plan(listOf(b(1, "A"), b(2, "B"), b(3, "C")))
        assertEquals(3, plan.unique.size)
        assertEquals(0, plan.removedCount)
    }
}
