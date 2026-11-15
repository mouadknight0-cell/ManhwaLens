package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class ShortDialogueOverridesTest {
    @Test fun noIsNotRenderedAsNumber() {
        assertEquals("لا.", ShortDialogueOverrides.apply("No.", "رقم"))
    }
    @Test fun imperativeRunIsTranslatedAsDialogue() {
        assertEquals("اهرب!", ShortDialogueOverrides.apply("Run!", "تشغيل!"))
    }
    @Test fun commonSoundEffectGetsArabicOnomatopoeia() {
        assertEquals("طَق!", ShortDialogueOverrides.apply("KRAK", "krak"))
    }
    @Test fun donePhraseIsNotTranslatedAsVerbToSeize() {
        assertEquals("يبدو أن الأمر انتهى!", ShortDialogueOverrides.apply("Looks like it's done!", "يبدو أنه انتهز!"))
    }
    @Test fun pieceOfCakeIdiomIsTranslatedByMeaning() {
        assertEquals("ها، هذا سهل للغاية.", ShortDialogueOverrides.apply("Ha, this is a piece of cake.", "ها، هذه قطعة من الكعكة."))
    }
    @Test fun magicalCoreUsesFantasyTerminologyBeforeGlossaryCanRewriteTheSource() {
        // "core" is a glossary term (الجوهرة), but this exact phrase needs its own
        // fantasy-specific rendering and must be matched against the untouched OCR source.
        assertEquals("النواة السحرية.", ShortDialogueOverrides.apply("The magical core", "The magical الجوهرة"))
    }
    @Test fun longerSentencesAreNeverOverridden() {
        val output = "شغّل البرنامج ثم انتظر"
        assertEquals(output, ShortDialogueOverrides.apply("Run the program and wait.", output))
    }
    @Test fun unrelatedShortTextIsUntouched() {
        assertEquals("نعم.", ShortDialogueOverrides.apply("Yes.", "نعم."))
    }
}
