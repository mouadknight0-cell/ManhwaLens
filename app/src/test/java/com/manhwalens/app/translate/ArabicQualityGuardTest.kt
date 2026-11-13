package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Output-guard regressions (v0.11.0) taken from the screenshots: a half-translated bubble
 * ("أنت ستستمر في الوصول من الآن. You're going to keep coming from now on.") survived the
 * old guard because the English came FIRST and only part of it repeated.
 */
class ArabicQualityGuardTest {

    // ---------------- partial translation ----------------

    @Test
    fun arabicFollowedByALongEnglishSentenceIsRejected() {
        val english = "You're going to keep coming from now on, okay? Except on weekends."
        val mixed = "أنت ستستمر في الوصول من الآن، حسنًا؟ You're going to keep coming from now on, okay? Except on weekends."
        // Either verdict is a rejection; the guard may report the echo first.
        val issue = ArabicValidator.issue(english, mixed)
        org.junit.Assert.assertNotNull(issue)
        org.junit.Assert.assertTrue(
            issue.toString(),
            issue == ArabicValidator.Issue.PARTIAL_TRANSLATION ||
                issue == ArabicValidator.Issue.UNTRANSLATED_ECHO
        )
    }

    @Test
    fun aPreservedCharacterNameIsNotAPartialTranslation() {
        assertNull(ArabicValidator.issue("Wait, Wei Wuxian!", "انتظر يا Wei Wuxian!"))
        assertNull(ArabicValidator.issue("Eren is here.", "Eren هنا."))
        assertNull(ArabicValidator.issue("Han Ji-woo, come.", "Han Ji-woo، تعال."))
    }

    @Test
    fun twoLatinWordsInArabicDialogueAreFine() {
        assertNull(ArabicValidator.issue("Use your Qi now.", "استخدم Qi الآن."))
    }

    @Test
    fun aSingleEnglishWordLeftInsideArabicIsToleratedOnce() {
        assertNull(ArabicValidator.issue("I will go home now.", "سأذهب home الآن."))
    }

    // ---------------- noise ----------------

    @Test
    fun punctuationOnlyOutputIsMeaningless() {
        assertEquals(
            ArabicValidator.Issue.MEANINGLESS_OUTPUT,
            ArabicValidator.issue("......", "......")
        )
        assertEquals(
            ArabicValidator.Issue.MEANINGLESS_OUTPUT,
            ArabicValidator.issue("......", "…")
        )
    }

    @Test
    fun aTranslatorLabelIsStrippedBeforeTheGuard() {
        val noisy = "الترجمة: هل أنت بخير؟"
        val clean = ArabicValidator.clean(noisy)
        assertEquals("هل أنت بخير؟", clean)
        assertNull(ArabicValidator.issue("Are you okay?", clean))
    }

    @Test
    fun wrappingQuotesAndZeroWidthMarksAreRemoved() {
        assertEquals("مرحبا", ArabicValidator.clean("\u200F\"مرحبا\"\u200E"))
    }

    @Test
    fun aTrailingTranslatorNoteIsRemoved() {
        assertEquals(
            "مرحبا بك",
            ArabicValidator.clean("مرحبا بك (translated from English)")
        )
    }

    // ---------------- typography / dialogue hygiene ----------------

    @Test
    fun polishKeepsArabicPunctuationTightAndClean() {
        assertEquals("هل أنت بخير؟ نعم، أنا بخير.", BubbleTextRules.polishArabic("هل أنت بخير ? نعم , أنا بخير."))
        // a punctuation-only line copied from the source is dropped
        assertEquals("انتظر", BubbleTextRules.polishArabic("انتظر\n……"))
    }

    @Test
    fun tidyDialogueCollapsesAnEchoedWordAndEllipsisRuns() {
        assertEquals("هو", BubbleTextRules.tidyDialogue("هو هو هو"))
        assertEquals("انتظر…", BubbleTextRules.tidyDialogue("انتظر......"))
        // real dialogue is untouched
        assertEquals("لا، لا أريد", BubbleTextRules.tidyDialogue("لا، لا أريد"))
    }

    @Test
    fun cachingTranslatorRejectsEchoButKeepsTheGoodBubbles() {
        val engine = object : Translator {
            override val engineId = "gemini"
            override fun translatePage(blocks: List<TranslationBlock>): PageTranslation =
                PageTranslation.Success(
                    blocks.map { b ->
                        // bubble 2 comes back as an English echo
                        TranslatedBlock(b.id, b.english, if (b.id == 2) b.english else "مرحبا")
                    },
                    engineId, 1, 0, 0
                )
        }
        val result = CachingTranslator(engine, TranslationCache())
            .translatePage(listOf(TranslationBlock(1, "Hello"), TranslationBlock(2, "What is this?")))
        val success = result as PageTranslation.Success
        assertEquals("مرحبا", success.blocks.first { it.id == 1 }.arabic)
        // the echoed English must never be painted, and must never be cached
        assertEquals("", success.blocks.first { it.id == 2 }.arabic)
    }
}
