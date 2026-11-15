package com.manhwalens.app.names

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 — the text surgery behind character memory.
 *
 * These are the rules that decide whether a correction reaches the reader, so they are pinned
 * down here: whole-word replacement only (never "Han" inside another word), alias aware, and
 * an Arabic repair that refuses to swap very short fragments (two-letter Arabic strings occur
 * inside ordinary words, and a blind replace would corrupt the sentence).
 */
class NameFormTest {

    @Test
    fun `normalize keeps letters only and lowercases`() {
        assertEquals("hanjiwoo", NameForm.normalize("Han Ji-woo"))
        assertEquals("weiwuxian", NameForm.normalize("  Wei Wuxian  "))
        assertEquals("hanji", NameForm.normalize("Han·Ji"))
        assertEquals("", NameForm.normalize("  - ' "))
    }

    @Test
    fun `normalize is stable for the same name spelled with hyphens or spaces`() {
        assertEquals(
            NameForm.normalize("Han Ji-woo"),
            NameForm.normalize("han ji woo")
        )
    }

    @Test
    fun `ambiguous english words are recognised`() {
        assertTrue(NameForm.isAmbiguousEnglishWord("Hope"))
        assertTrue(NameForm.isAmbiguousEnglishWord("may"))
        assertFalse(NameForm.isAmbiguousEnglishWord("Han Ji-woo"))
        assertFalse(NameForm.isAmbiguousEnglishWord("Wei Wuxian"))
    }

    @Test
    fun `appearsIn matches whole words only`() {
        assertTrue(NameForm.appearsIn("Then Han Ji-woo left.", "Han Ji-woo"))
        assertTrue(NameForm.appearsIn("HAN JI-WOO!", "Han Ji-woo"))
        // A substring of a longer word is not the character.
        assertFalse(NameForm.appearsIn("Hanjisoo left.", "Han Ji-woo"))
        assertFalse(NameForm.appearsIn("The hand is cold.", "Han"))
    }

    @Test
    fun `appearsIn knows every alias of a record`() {
        val record = record(aliases = listOf("Jiwoo"))
        assertTrue(NameForm.appearsIn("Jiwoo is late.", record.originalName, record.aliases))
        assertFalse(NameForm.appearsIn("Ji-woon is late.", record.originalName, record.aliases))
    }

    @Test
    fun `replaceLatin swaps the whole name and keeps the rest of the sentence`() {
        val out = NameForm.replaceLatin("انتظر يا Han Ji-woo من فضلك", "هان جيو", listOf("Han Ji-woo"))
        assertEquals("انتظر يا هان جيو من فضلك", out)
    }

    @Test
    fun `replaceLatin uses aliases too`() {
        val out = NameForm.replaceLatin(
            "قالت Ji-woo ذلك",
            "هان جيو",
            listOf("Han Ji-woo", "Ji-woo")
        )
        assertEquals("قالت هان جيو ذلك", out)
    }

    @Test
    fun `replaceLatin does not touch a longer word containing the name`() {
        val text = "Hanjisoo left"
        assertEquals(text, NameForm.replaceLatin(text, "هان جيو", listOf("Han")))
    }

    @Test
    fun `replaceArabic repairs a superseded spelling`() {
        val out = NameForm.replaceArabic("قلت هان جي وو مرحبا", "هان جي وو", "هان جيو")
        assertEquals("قلت هان جيو مرحبا", out)
        // Whole-word only: a form glued to a preceding letter (لهان…) is left alone.
        assertEquals("قلت لهان جي وو مرحبا", NameForm.replaceArabic("قلت لهان جي وو مرحبا", "هان جي وو", "هان جيو"))
    }

    @Test
    fun `replaceArabic refuses fragments shorter than three letters`() {
        // "جي" occurs inside ordinary words; replacing it blindly would corrupt the text.
        val text = "جي"
        assertEquals(text, NameForm.replaceArabic(text, "جي", "هان جيو"))
    }

    @Test
    fun `spellings is original first then aliases`() {
        val record = record(aliases = listOf("Jiwoo", "Ji Woo"))
        assertEquals(listOf("Han Ji-woo", "Jiwoo", "Ji Woo"), NameForm.spellings(record))
    }

    private fun record(aliases: List<String>) = CharacterRecord(
        id = "s1-1",
        seriesId = "s1",
        originalName = "Han Ji-woo",
        normalizedName = NameForm.normalize("Han Ji-woo"),
        arabicName = "هان جيو",
        script = com.manhwalens.app.translate.NameScript.KOREAN,
        aliases = aliases
    )
}
