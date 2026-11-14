package com.manhwalens.app.translate

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scene-name tests (v0.11.0). The reader's complaint was concrete: Chinese and Korean names
 * came back spelled differently on every bubble. These tests pin the behaviour.
 */
class NameMemoryTest {

    @After
    fun reset() = Glossary.refresh("", true)

    // ---------------- script detection ----------------

    @Test
    fun chineseKoreanAndJapaneseRomanizationsAreToldApart() {
        assertEquals(NameScript.CHINESE, NameScript.of("Wei Wuxian"))
        assertEquals(NameScript.CHINESE, NameScript.of("Xiao Yan"))
        assertEquals(NameScript.CHINESE, NameScript.of("Zhang"))
        assertEquals(NameScript.KOREAN, NameScript.of("Han Ji-woo"))
        assertEquals(NameScript.KOREAN, NameScript.of("Ji-woo"))
        assertEquals(NameScript.KOREAN, NameScript.of("Seo-yeon"))
        assertEquals(NameScript.JAPANESE, NameScript.of("Sasuke"))
        assertEquals(NameScript.JAPANESE, NameScript.of("Takeshi"))
        assertEquals(NameScript.UNKNOWN, NameScript.of("Michael"))
        assertEquals(NameScript.UNKNOWN, NameScript.of("Anyway"))
    }

    // ---------------- scanning ----------------

    @Test
    fun sentenceInitialEnglishWordsAreNotNames() {
        val texts = listOf(
            "Anyway, jogging every day feels refreshing, right?",
            "You're going to keep coming from now on, okay?",
            "Except on weekends.",
            "Let's go hang out in my room."
        )
        val mentions = NameMemory.scan(texts).map { it.text }
        assertTrue("no names expected, got $mentions", mentions.isEmpty())
    }

    @Test
    fun midSentenceProperNounsAreDetected() {
        val texts = listOf("I told Wei Lin to wait.", "Wei Lin never listens.")
        val mentions = NameMemory.scan(texts).map { it.text }
        assertTrue(mentions.toString(), mentions.any { it.contains("Wei") })
    }

    @Test
    fun allCapsSoundEffectsAreNeverNames() {
        assertFalse(NameMemory.looksLikeName("KRAK", midSentence = true))
        assertFalse(NameMemory.looksLikeName("BOOM", midSentence = true))
        assertTrue(NameMemory.scan(listOf("KRAK! BOOM!")).isEmpty())
    }

    @Test
    fun multiWordPhrasesAreJoinedAndTitlesSplit() {
        val phrases = NameMemory.phrases("Young Master Wei Wuxian drew his sword.")
        assertTrue(phrases.toString(), phrases.any { it.contains("Wei Wuxian") })
        val (title, name) = NameMemory.stripTitle("Wei Wuxian")
        assertNull(title)
        assertEquals("Wei Wuxian", name)
        val (t2, n2) = NameMemory.stripTitle("Young Master Wei")
        assertEquals("young master", t2)
        assertEquals("Wei", n2)
    }

    // ---------------- transliteration ----------------

    @Test
    fun koreanNamesGetAStableArabicSpelling() {
        val a = KoreanRomanizer.toArabic("Ji-woo")
        val b = KoreanRomanizer.toArabic("Jiwoo")
        assertNotNull(a)
        assertEquals("both spellings of the same name must agree", a, b)
        assertEquals("جي وو", a)
        // A surname + given name keeps a single space between the blocks.
        val full = KoreanRomanizer.toArabic("Han Ji-woo")
        assertNotNull(full)
        assertTrue(full!!, full.startsWith("هان"))
    }

    @Test
    fun koreanSpellingDoesNotInventArabicForUnknownTokens() {
        assertNull(KoreanRomanizer.toArabic("Michael"))
        assertNull(KoreanRomanizer.toArabic("Anyway"))
    }

    @Test
    fun pagePlanPinsOneSpellingPerName() {
        Glossary.refresh("", true)
        val texts = listOf(
            "Han Ji-woo, wait for me!",
            "Ji-woo is here.",
            "I told Han Ji-woo already."
        )
        val plan = NameMemory.plan(texts)
        assertFalse("expected at least one pinned name", plan.isEmpty)
        // One spelling per person: the full name is pinned, and the given-name-only variant
        // collapses into it (its Arabic is a suffix of the full Arabic form).
        val full = plan.table.entries.first { NameMemory.looseKey(it.key) == "hanjiwoo" }.value
        assertEquals("هان جي وو", full)
        assertTrue(
            "the fragment must not appear as a second spelling",
            plan.table.keys.none { NameMemory.looseKey(it) == "jiwoo" }
        )
    }

    @Test
    fun userGlossaryEntryWinsOverEverything() {
        Glossary.refresh("Han Ji-woo = هان جيوو", true)
        val plan = NameMemory.plan(listOf("Han Ji-woo is here.", "Han Ji-woo!"))
        val pinned = plan.pinned.firstOrNull { NameMemory.looseKey(it.text) == "hanjiwoo" }
        assertNotNull("user name must be pinned", pinned)
        assertEquals("هان جيوو", pinned!!.arabic)
        assertEquals(NameMemory.Pinned.Origin.USER, pinned.origin)
    }

    @Test
    fun builtInTableSpellingIsReused() {
        Glossary.refresh("", true)
        val plan = NameMemory.plan(listOf("Lan Wangji drew his sword.", "Lan Wangji said nothing."))
        val pinned = plan.pinned.firstOrNull { it.text.equals("Lan Wangji", ignoreCase = true) }
        assertNotNull(pinned)
        assertEquals("لان وانغجي", pinned!!.arabic)
        assertEquals(NameMemory.Pinned.Origin.BUILT_IN, pinned.origin)
    }

    @Test
    fun nameSheetIsShortAndOrdered() {
        Glossary.refresh("", true)
        val plan = NameMemory.plan(listOf("Wei Wuxian met Lan Wangji at the gate."))
        val sheet = NameMemory.sheet(plan)
        assertTrue(sheet, sheet.contains("="))
        assertTrue(sheet.split(";").size <= 20)
    }

    @Test
    fun pinnedNamesReplaceLatinRemnantsInsideArabicOutput() {
        val table = mapOf("Lan Wangji" to "لان وانغجي")
        val out = NameMemory.applyPinned("انتظر يا Lan Wangji من فضلك", "Wait, Lan Wangji.", table)
        assertFalse(out, out.contains("Lan"))
        assertTrue(out, out.contains("لان وانغجي"))
        // unrelated Arabic is untouched
        assertEquals("انتظر هنا", NameMemory.applyPinned("انتظر هنا", "Wait here.", table))
    }

    @Test
    fun sceneNamesOnlyPinsWhatItCanTrust() {
        val table = SceneNames.pin(
            listOf(
                "Han Ji-woo is here.",
                "Ji-woo, come.",
                "Anyway, jogging every day feels refreshing, right?"
            )
        )
        assertTrue(table.toString(), table.keys.any { it.contains("Ji", ignoreCase = true) })
        assertFalse("ordinary dialogue must not be pinned", table.keys.any { it.equals("Anyway", true) })
    }

    // ------------------------------------------------------------------
    // P2-18: an everyday English word must not become a character name by
    // accident — the same rule the glossary already applies (P2-01), reused.
    // ------------------------------------------------------------------

    /** The planner's ambiguity gate must not fire on a candidate the scanner never made. */
    @Test
    fun theAmbiguityGateOnlySilencesRealCandidates() {
        // Both words are ordinary English *and* built-in table keys …
        assertTrue(Glossary.isAmbiguousEnglish("Sun"))
        assertTrue(Glossary.isAmbiguousEnglish("Long"))
        // … and the scanner really would offer them, so the gate is what removes them:
        // "Sun" sits mid-sentence, "Long" opens one (KNOWN_SENTENCE_NAMES).
        assertTrue(NameMemory.looksLikeName("Sun", midSentence = true))
        assertTrue(NameMemory.looksLikeName("Long", midSentence = false))
    }

    @Test
    fun ambiguousEnglishWordsAreNotAutoPinnedAsNames() {
        Glossary.refresh("", true)
        val plan = NameMemory.plan(
            listOf(
                "The Sun was hot and the road was long.",
                "Long ago, the road was long."
            )
        )
        val keys = plan.table.keys.map { NameMemory.looseKey(it) }
        assertFalse("Sun must not be pinned: ${plan.table}", "sun" in keys)
        assertFalse("Long must not be pinned: ${plan.table}", "long" in keys)
        assertTrue(
            "no candidate may carry those words: ${plan.pinned.map { it.text }}",
            plan.pinned.none { NameMemory.looseKey(it.text) in setOf("sun", "long") }
        )
        // The name sheet is what the model is told, so it must stay silent as well.
        val sheet = NameMemory.sheet(plan)
        assertFalse(sheet, sheet.contains("سون"))
        assertFalse(sheet, sheet.contains("لونغ"))
    }

    @Test
    fun aUserEntryForAnAmbiguousWordIsStillPinned() {
        // The reader's own Settings line is evidence, not a guess: it must survive the gate.
        Glossary.refresh("Sun = شمسي", true)
        val plan = NameMemory.plan(listOf("The Sun was hot and the road was long."))
        val pinned = plan.pinned.firstOrNull { NameMemory.looseKey(it.text) == "sun" }
        assertNotNull("the reader's ambiguous name must still be pinned", pinned)
        assertEquals("شمسي", pinned!!.arabic)
        assertEquals(NameMemory.Pinned.Origin.USER, pinned.origin)
        assertTrue(NameMemory.sheet(plan), NameMemory.sheet(plan).contains("Sun = شمسي"))
    }

    @Test
    fun multiWordNamesWithAnAmbiguousWordStillPin() {
        Glossary.refresh("", true)
        val plan = NameMemory.plan(
            listOf("Song Lan drew his sword.", "Song Lan said nothing.")
        )
        val pinned = plan.pinned.firstOrNull { it.text.equals("Song Lan", ignoreCase = true) }
        assertNotNull("a multi-word name must not be filtered: ${plan.pinned.map { it.text }}", pinned)
        assertEquals("سونغ لان", pinned!!.arabic)
        // And the bare noun on its own is still refused.
        assertTrue(
            "no bare Song",
            plan.pinned.none { NameMemory.looseKey(it.text) == "song" }
        )
    }

    @Test
    fun unambiguousSingleWordNamesAreUnaffected() {
        Glossary.refresh("", true)
        val plan = NameMemory.plan(listOf("Ming is here.", "Ming left."))
        val pinned = plan.pinned.firstOrNull { NameMemory.looseKey(it.text) == "ming" }
        assertNotNull("an unambiguous one-word name must still pin", pinned)
        assertEquals("مينغ", pinned!!.arabic)
        assertEquals(NameMemory.Pinned.Origin.BUILT_IN, pinned.origin)
    }

    @Test
    fun theRepairNeverRewritesCorrectArabic() {
        val table = mapOf("Han Ji-woo" to "هان جي وو")
        // P2-05 keeps applyPinned's contract: it repairs LATIN remnants only.
        assertEquals(
            "correct Arabic of the same name must survive",
            "انتظر يا هان جيو من فضلك",
            NameMemory.applyPinned("انتظر يا هان جيو من فضلك", "Wait, Han Ji-woo.", table)
        )
        // A different Arabic spelling is NOT rewritten by the engine pass (that is the decorator's
        // previousArabic repair, and P2-14/P2-15 territory) — no blind Arabic substitution.
        assertEquals(
            "قال هان جيو ذلك",
            NameMemory.applyPinned("قال هان جيو ذلك", "Han Ji-woo said that.", table)
        )
    }

    @Test
    fun sceneNamesNeverOverrideTheGlossary() {
        Glossary.refresh("", true)
        // Lan Wangji is already in the built-in table → Glossary.prepare handles it.
        val table = SceneNames.pin(listOf("Lan Wangji is here.", "Lan Wangji left."))
        assertFalse(table.toString(), table.keys.any { it.equals("Lan Wangji", ignoreCase = true) })
    }
}
