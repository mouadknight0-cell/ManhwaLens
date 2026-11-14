package com.manhwalens.app.translate

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryTest {

    @After
    fun reset() = Glossary.refresh("", true)

    @Test
    fun knownNamesAreArabic_beforeTheEngineSeesThem() {
        Glossary.refresh("", true)
        val out = Glossary.prepare("Wei Wuxian, wait for Lan Wangji.")
        assertTrue(out, out.contains("وي ووشيان"))
        assertTrue(out, out.contains("لان وانغجي"))
        assertFalse("the Latin name must be gone", out.contains("Wuxian"))
    }

    @Test
    fun userEntriesWinOverTheBuiltInTable() {
        Glossary.refresh("Wei Wuxian = ويي ووشيان\nHua Cheng,هوا تشنغ", true)
        val out = Glossary.prepare("Wei Wuxian and Hua Cheng")
        assertTrue(out, out.contains("ويي ووشيان"))
        assertTrue(out, out.contains("هوا تشنغ"))
        assertEquals(2, Glossary.userEntries("Wei Wuxian = ويي ووشيان\nHua Cheng,هوا تشنغ\nnonsense line").size)
    }

    @Test
    fun ordinaryEnglishWordsAreNotTouched() {
        Glossary.refresh("", true)
        val sentence = "He will come with the army to the river and she knows it."
        assertEquals(sentence, Glossary.prepare(sentence))
    }

    @Test
    fun unknownPinyinNameIsTransliterated_andEnglishStaysLatin() {
        Glossary.refresh("", true)
        // "Zixiao" parses as pinyin → Arabic; "Michael" does not → untouched.
        val out = Glossary.prepare("Zixiao met Michael at the gate.")
        assertFalse("pinyin name should be transliterated: $out", out.contains("Zixiao"))
        assertTrue(out, out.contains("Michael"))
    }

    @Test
    fun ordinaryCapitalizedWords_areNeverTransliterated() {
        Glossary.refresh("", true)
        val out = Glossary.prepare("Bubble number 1 here")
        assertEquals("Bubble number 1 here", out)
        assertTrue(Glossary.looksChinese("xiao"))
        assertFalse(Glossary.looksChinese("line"))
    }

    @Test
    fun linesOfOneBubbleBecomeOneSentence() {
        Glossary.refresh("", true)
        assertEquals(
            "I told you to stay behind me because the gate is watched.",
            Glossary.prepare("I told you to stay behind me\nbecause the gate is watched.")
        )
        // a real sentence end keeps its mark and still yields one string
        assertEquals(
            "Run! They are here.",
            Glossary.prepare("Run!\nThey are here.")
        )
        // hyphenated wrap heals
        assertEquals("I can still stand.", Glossary.prepare("I can still\nstand."))
    }

    @Test
    fun mergeCanBeTurnedOff() {
        Glossary.refresh("", false)
        assertTrue(Glossary.prepare("one\ntwo").contains("\n"))
        assertFalse(Glossary.cacheText("one\ntwo").contains("\n").not())
    }

    @Test
    fun preparingAHundredBlocksStaysCheap() {
        Glossary.refresh("", true)
        val t0 = System.nanoTime()
        repeat(100) { Glossary.prepare("Wei Wuxian drew his sword and waited for Lan Wangji to speak.") }
        val ms = (System.nanoTime() - t0) / 1_000_000
        org.junit.Assert.assertTrue("100 blocks took ${'$'}ms ms", ms < 400)
    }

    // ---------------------------------------------------------------- P2-01: false positives

    @Test
    fun ordinaryLongAndSunStayEnglish() {
        Glossary.refresh("", true)
        // Both are real table keys ("Long" لونغ, "Sun" سون); neither is a name here.
        assertEquals(
            "How long until the sun sets?",
            Glossary.prepare("How long until the sun sets?")
        )
        assertEquals(
            "The long road was hot and the sun was in his eyes.",
            Glossary.prepare("The long road was hot and the sun was in his eyes.")
        )
        assertFalse(Glossary.prepare("How long?").contains("لونغ"))
        assertFalse(Glossary.prepare("The sun rose.").contains("سون"))
    }

    @Test
    fun sentenceInitialLongAndSunAreStillOrdinaryWords() {
        Glossary.refresh("", true)
        // Capitalisation and sentence position are NOT evidence of a name (P2-01).
        assertEquals(
            "Long ago, the road was empty.",
            Glossary.prepare("Long ago, the road was empty.")
        )
        assertEquals(
            "Sun was in his eyes and the road was long.",
            Glossary.prepare("Sun was in his eyes and the road was long.")
        )
        assertFalse(Glossary.prepare("Long before you were born.").contains("لونغ"))
        assertFalse(Glossary.prepare("Sun poured through the window.").contains("سون"))
    }

    @Test
    fun shortCollisionsStayEnglish() {
        Glossary.refresh("", true)
        assertEquals(
            "She put the pan on the fire and used the tan cloth.",
            Glossary.prepare("She put the pan on the fire and used the tan cloth.")
        )
        assertEquals("He heard a ding from the bell.", Glossary.prepare("He heard a ding from the bell."))
        // "Song" is a table key (سونغ) and an ordinary noun; the genre term next to it still works.
        assertEquals("He sang a Song for the sect.", Glossary.prepare("He sang a Song for the sect."))
        assertFalse(Glossary.prepare("A Song in the hall.").contains("سونغ"))
    }

    @Test
    fun termsStillTranslateInAnyCase() {
        Glossary.refresh("", true)
        // P2-01 must not weaken the term dictionary: these are the table's subject matter.
        val out = Glossary.prepare("The sect kept the core warm for his qi.")
        assertTrue(out, out.contains("طائفة"))
        assertTrue(out, out.contains("الجوهرة"))
        assertTrue(out, out.contains("تشي"))
        assertTrue(Glossary.prepare("Yes, shidi.").contains("الأخ الأصغر"))
    }

    @Test
    fun genuineNamesStillTranslate() {
        Glossary.refresh("", true)
        val out = Glossary.prepare("Song Lan waited for Wei Wuxian.")
        assertTrue(out, out.contains("سونغ لان"))
        assertTrue(out, out.contains("وي ووشيان"))
        assertFalse("the Latin name must be gone: $out", out.contains("Wuxian"))
        assertFalse("the Latin name must be gone: $out", out.contains("Song Lan"))
        // A one-word name keeps working when it is written as a two-word name.
        assertEquals("سونغ لان drew his sword.", Glossary.prepare("Song Lan drew his sword."))
    }

    @Test
    fun aPinnedNameIsAlwaysHonoured() {
        // The reader's own Settings line is explicit evidence: it wins over every rule above.
        Glossary.refresh("Long = لونغ", true)
        assertEquals("لونغ is here.", Glossary.prepare("Long is here."))
        // Only what the reader pinned is affected.
        assertEquals("A Song in the hall.", Glossary.prepare("A Song in the hall."))
    }

    @Test
    fun thePinIsScopedToTheCurrentSettings() {
        Glossary.refresh("Long = لونغ", true)
        assertTrue(Glossary.prepare("Long is here.").contains("لونغ"))
        Glossary.refresh("", true)
        assertEquals("Long is here.", Glossary.prepare("Long is here."))
    }

    @Test
    fun theTableStillCarriesTheNameForTheMemory() {
        Glossary.refresh("", true)
        // P2-01 only refuses the replacement, it does not forget the name: the memory planner
        // (SceneNames.pin / NameMemory) reads effective(), so "Long" is still offerable.
        assertEquals("لونغ", Glossary.effective()["Long"])
        assertEquals("سونغ", Glossary.effective()["Song"])
        // …and the memory path still converts a name the glossary now leaves alone.
        assertEquals(
            "قال لونغ ذلك",
            com.manhwalens.app.names.NameForm.replaceLatin("قال Long ذلك", "لونغ", listOf("Long"))
        )
    }

    @Test
    fun cacheKeyFollowsThePreparedText() {
        Glossary.refresh("", true)
        val plain = TranslationCache.normalize(Glossary.cacheText("Wei Wuxian\nis here"))
        assertTrue(plain, plain.contains("وي ووشيان"))
    }
}
