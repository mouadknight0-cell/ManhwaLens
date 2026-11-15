package com.manhwalens.app.names

import com.manhwalens.app.translate.NameScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 — one page table feeds both engines.
 *
 * [PageNameTable.table] is what ML Kit substitutes inline; [PageNameTable.sheet] is what Gemini
 * is told. They come from the same resolved list, so "the two engines agree" is a property of
 * this class rather than a hope about prompt wording.
 */
class PageNameTableTest {

    @Test
    fun `table is longest first so a full name wins over its fragment`() {
        val page = PageNameTable(
            listOf(
                resolved("Wei", "وي"),
                resolved("Wei Wuxian", "وي ووشيان")
            ),
            seriesId = "s1"
        )

        assertEquals(listOf("Wei Wuxian", "Wei"), page.table.keys.toList())
        assertEquals("وي ووشيان", page.table["Wei Wuxian"])
    }

    @Test
    fun `names without arabic stay out of the table`() {
        val page = PageNameTable(
            listOf(resolved("Wang", null), resolved("Lan Wangji", "لان وانغجي")),
            seriesId = "s1"
        )

        assertEquals(1, page.table.size)
        assertNull(page.arabicFor("Wang"))
        assertEquals("لان وانغجي", page.arabicFor("lan wangji"))
    }

    @Test
    fun `sheet keeps the documented format and its limit`() {
        val names = (1..25).map { resolved("Name$it", "اسم$it") }
        val sheet = PageNameTable(names, "s1").sheet

        assertTrue(sheet.startsWith("Name1 = اسم1; Name2 = اسم2"))
        assertEquals(PageNameTable.SHEET_LIMIT - 1, sheet.count { it == ';' })
    }

    @Test
    fun `describe names the reason for the first few entries`() {
        val text = PageNameTable(listOf(resolved("Han Ji-woo", "هان جيو")), "s1").describe()
        assertTrue(text.contains("Han Ji-woo"))
        assertTrue(text.contains("locked"))
    }

    @Test
    fun `an empty table is empty`() {
        assertTrue(PageNameTable.EMPTY.isEmpty)
        assertEquals(0, PageNameTable.EMPTY.pinnedCount)
        assertEquals("", PageNameTable.EMPTY.sheet)
        assertEquals("no names", PageNameTable.EMPTY.describe())
    }

    private fun resolved(original: String, arabic: String?, reason: String = "locked") = ResolvedName(
        original = original,
        arabic = arabic,
        script = NameScript.CHINESE,
        confidence = 1f,
        source = NameSource.USER,
        locked = true,
        recordId = null,
        persistable = false,
        reason = reason
    )
}
