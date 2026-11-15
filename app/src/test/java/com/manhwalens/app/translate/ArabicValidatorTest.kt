package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArabicValidatorTest {

    @Test
    fun acceptsArabicAndKeepsLatinCharacterNames() {
        assertNull(ArabicValidator.issue("Come here, Eren!", "تعال إلى هنا يا Eren!"))
    }

    @Test
    fun rejectsEnglishEchoForOrdinaryDialogue() {
        assertEquals(
            ArabicValidator.Issue.UNTRANSLATED_ECHO,
            ArabicValidator.issue("Yes!", "Yes!")
        )
    }

    @Test
    fun allowsAnUnfamiliarStandaloneProperNameToRemainUnchanged() {
        assertNull(ArabicValidator.issue("Eren", "Eren"))
    }

    @Test
    fun acceptsArabicSentenceWithAProperNameButRejectsEnglishDialogueEcho() {
        assertNull(ArabicValidator.issue("Wait, Eren!", "انتظر يا Eren!"))
        assertEquals(
            ArabicValidator.Issue.UNTRANSLATED_ECHO,
            ArabicValidator.issue("What is this?", "ما هذا؟ What is this?")
        )
    }

    @Test
    fun rejectsEnglishOnlyOutputThatIsNotAnExactProperName() {
        assertEquals(
            ArabicValidator.Issue.NO_ARABIC_SCRIPT,
            ArabicValidator.issue("Come here.", "Come over here.")
        )
    }

    @Test
    fun rejectsTwoUntranslatedEnglishWordsInsideArabic() {
        assertEquals(
            ArabicValidator.Issue.UNTRANSLATED_FRAGMENT,
            ArabicValidator.issue("I will help you tomorrow.", "سأساعدك will you غدًا")
        )
        assertEquals(
            ArabicValidator.Issue.UNTRANSLATED_FRAGMENT,
            ArabicValidator.issue("The door is open.", "الباب the is مفتوح")
        )
    }

    @Test
    fun detectsCommonEnglishWordsHiddenByOcrApostrophes() {
        assertEquals(
            ArabicValidator.Issue.PARTIAL_TRANSLATION,
            ArabicValidator.issue(
                "I barely pay my sick mother's medical bills with what the hunter guild pays me.",
                "أنا أدفع فواتير والدتي المريضة مع s'What the hunter guild يدفع لي"
            )
        )
    }

    @Test
    fun acceptsArabicWithAnApostropheInAnUnfamiliarProperName() {
        assertNull(
            ArabicValidator.issue(
                "Come here, O'Neil!",
                "تعال إلى هنا يا O'Neil!"
            )
        )
    }

    @Test
    fun acceptsPreservedNamesAndSoundEffectsInsideArabic() {
        assertNull(ArabicValidator.issue("Come here, Eren!", "تعال إلى هنا يا Eren!"))
        assertNull(
            ArabicValidator.issue(
                "A loud KRAK echoed through the hall.",
                "دوّى صوت KRAK في القاعة"
            )
        )
    }

    @Test
    fun acceptsLegitimateShortArabicDialogue() {
        assertNull(ArabicValidator.issue("Yes!", "نعم"))
        assertNull(ArabicValidator.issue("No!", "لا"))
    }

    @Test
    fun rejectsExtremelyShortArabicOutputForALongSource() {
        val source = "I remember the night when you came to this village and told everyone that the old king had returned from the northern mountains after many years away."
        assertEquals(
            ArabicValidator.Issue.INCOMPLETE_TRANSLATION,
            ArabicValidator.issue(source, "نعم")
        )
    }

    @Test
    fun doesNotRejectModeratelyShortArabicOutputForALongSource() {
        val source = "I remember the night when you came to this village and told everyone that the old king had returned from the northern mountains after many years away."
        assertNull(
            ArabicValidator.issue(source, "أتذكر تلك الليلة جيدًا، ولن أنساها أبدًا")
        )
    }

    @Test
    fun rejectsEmptyAndMalformedOutput() {
        assertEquals(ArabicValidator.Issue.EMPTY_OUTPUT, ArabicValidator.issue("Hello", "  "))
        assertEquals(ArabicValidator.Issue.MALFORMED_OUTPUT, ArabicValidator.issue("Hello", "مرحبًا\u0001"))
        assertEquals(ArabicValidator.Issue.MALFORMED_OUTPUT, ArabicValidator.issue("Hello", "مرحبًا\uFFFD"))
    }
}
