package com.manhwalens.app.ocr

import com.manhwalens.app.ocr.geometry.Rect2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the "delete what has no meaning" rule set (v0.11.0), written from
 * real reader screenshots: an ellipsis bubble, the app's own "Scanning / Same page"
 * caption, a scanlator watermark, a chapter header, and the SFX the old filter kept.
 */
class MeaninglessTextTest {

    private fun block(id: Int, text: String) = BubbleBlock(
        id = id,
        lines = listOf(DetectedLine(text = text, box = Rect2D(0f, 300f, 400f, 360f)))
    )

    private fun drop(text: String) = MeaninglessText.classify(text)

    // ---------------- punctuation / fragments ----------------

    @Test
    fun ellipsisOnlyBubbleIsDropped() {
        // The screenshot's bottom bubble: "......" with no words at all.
        for (t in listOf("......", "…", "…!", "...", "—", "!?", "♪", "~~~~")) {
            val v = drop(t)
            assertTrue("text=$t should drop", !v.keep)
            assertEquals("text=$t", MeaninglessText.Reason.PUNCTUATION_ONLY, v.reason)
        }
    }

    @Test
    fun numbersAndDecorationAreDropped() {
        for (t in listOf("#24", "x2", "1.", "①", "< 3", "— —")) {
            assertTrue("text=$t should drop", !drop(t).keep)
        }
    }

    @Test
    fun shortOcrFragmentsAreDroppedButRealShortDialogueSurvives() {
        assertTrue(!drop("N G").keep)
        assertTrue(!drop("R T").keep)
        // every one of these is dialogue a reader must see
        for (t in listOf("OK", "NO", "GO", "HI", "YES!", "WHAT?!", "HELLO", "MAYBE.", "SORRY.")) {
            assertTrue("text=$t must survive", drop(t).keep)
        }
    }

    // ---------------- sound effects ----------------

    @Test
    fun soundEffectsAreDropped() {
        for (t in listOf("KRAK!", "BOOM", "KRAK BOOM!", "THUD THUD THUD", "GRRRR", "BZZZ", "SLAM!")) {
            assertTrue("text=$t should drop", !drop(t).keep)
        }
    }

    @Test
    fun aBubbleThatMixesSoundAndSpeechIsKept() {
        assertTrue(drop("KRAK! The wall crumbles.").keep)
        assertTrue(drop("THUD — is anyone there?").keep)
    }

    // ---------------- watermarks / own UI / chapter furniture ----------------

    @Test
    fun watermarksAndScanlatorCreditsAreDropped() {
        for (t in listOf(
            "www.mangadex.com",
            "Read at Tapas",
            "discord.gg/abc",
            "Translator: Kira",
            "Scanlation Team",
            "EN",
            "TR",
            "AR/EN",
            "© 2024 Studio"
        )) {
            assertTrue("text=$t should drop", !drop(t).keep)
        }
    }

    @Test
    fun ourOwnOverlayCaptionIsDropped() {
        // "Same page" / "Scanning" are burned into the screenshot the OCR reads.
        val own = setOf("same page", "scanning", "translating", "watching")
        assertTrue(!MeaninglessText.classify("Same page", own).keep)
        assertTrue(!MeaninglessText.classify("Scanning", own).keep)
        assertEquals(
            MeaninglessText.Reason.APP_CHROME,
            MeaninglessText.classify("same page", own).reason
        )
        // A real line that merely contains the word "page" must survive.
        assertTrue(drop("Turn to the next page.").keep)
    }

    @Test
    fun chapterHeadersAreDroppedAndDialogueMentioningAChapterIsKept() {
        for (t in listOf("Chapter 23", "Ch. 23", "Episode 5", "Ep 12", "Page 7", "Vol. 3")) {
            assertTrue("text=$t should drop", !drop(t).keep)
        }
        assertTrue(drop("I read that chapter already.").keep)
    }

    @Test
    fun arabicAlreadyOnThePageIsNeverRetranslated() {
        val v = drop("أنا بخير")
        assertTrue(!v.keep)
        assertEquals(MeaninglessText.Reason.ALREADY_ARABIC, v.reason)
    }

    // ---------------- the real dialogue from the screenshots ----------------

    @Test
    fun theScreenshotDialogueIsKept() {
        val lines = listOf(
            "ANYWAY, JOGGING EVERY DAY FEELS REFRESHING, RIGHT?",
            "YOU'RE GOING TO KEEP COMING FROM NOW ON, OKAY? EXCEPT ON WEEKENDS.",
            "LET'S GO HANG OUT IN MY ROOM."
        )
        for (t in lines) assertTrue("must keep: $t", drop(t).keep)
    }

    @Test
    fun blockFilterUsesTheSharedVerdict() {
        val blocks = listOf(
            block(1, "ANYWAY, JOGGING EVERY DAY FEELS REFRESHING, RIGHT?"),
            block(2, "......"),
            block(3, "Translator: Kira"),
            block(4, "LET'S GO HANG OUT IN MY ROOM.")
        )
        val result = BlockFilter.apply(blocks)
        assertEquals(listOf(1, 4), result.kept.map { it.id })
        val reasons = result.removed.associate { it.id to it.reason }
        // "......" is caught by BlockFilter's older symbols-only rule before the shared
        // verdict runs; either way the bubble must not be translated.
        assertTrue(reasons[2], reasons[2] == "punctuation-only" || reasons[2] == "symbols-only")
        assertEquals("watermark", reasons[3])
    }

    @Test
    fun pagePlanDropsNoiseBeforeTheEngineSeesIt() {
        val blocks = listOf(
            com.manhwalens.app.translate.TranslationBlock(1, "LET'S GO HANG OUT IN MY ROOM."),
            com.manhwalens.app.translate.TranslationBlock(2, "......"),
            com.manhwalens.app.translate.TranslationBlock(3, "Chapter 23")
        )
        val plan = com.manhwalens.app.translate.PagePlan.prepare(blocks)
        assertEquals(1, plan.uniqueCount)
        assertEquals(2, plan.droppedCount)
        assertEquals(listOf(1), plan.idToKey.keys.toList())
    }

    @Test
    fun repeatedLinesAreTranslatedOncePerPage() {
        val blocks = listOf(
            com.manhwalens.app.translate.TranslationBlock(1, "What?"),
            com.manhwalens.app.translate.TranslationBlock(2, "what?"),
            com.manhwalens.app.translate.TranslationBlock(3, "What?"),
            com.manhwalens.app.translate.TranslationBlock(4, "I know.")
        )
        val plan = com.manhwalens.app.translate.PagePlan.prepare(blocks)
        assertEquals(2, plan.uniqueCount)
        assertEquals(4, plan.idToKey.size)
    }
}
