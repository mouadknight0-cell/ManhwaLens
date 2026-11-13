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

    @Test
    fun cacheKeyFollowsThePreparedText() {
        Glossary.refresh("", true)
        val plain = TranslationCache.normalize(Glossary.cacheText("Wei Wuxian\nis here"))
        assertTrue(plain, plain.contains("وي ووشيان"))
    }
}
