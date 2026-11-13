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
    fun rejectsEmptyAndMalformedOutput() {
        assertEquals(ArabicValidator.Issue.EMPTY_OUTPUT, ArabicValidator.issue("Hello", "  "))
        assertEquals(ArabicValidator.Issue.MALFORMED_OUTPUT, ArabicValidator.issue("Hello", "مرحبًا\u0001"))
        assertEquals(ArabicValidator.Issue.MALFORMED_OUTPUT, ArabicValidator.issue("Hello", "مرحبًا\uFFFD"))
    }
}
