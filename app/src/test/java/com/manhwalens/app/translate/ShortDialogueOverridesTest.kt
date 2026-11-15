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
    @Test fun longerSentencesAreNeverOverridden() {
        val output = "شغّل البرنامج ثم انتظر"
        assertEquals(output, ShortDialogueOverrides.apply("Run the program and wait.", output))
    }
    @Test fun unrelatedShortTextIsUntouched() {
        assertEquals("نعم.", ShortDialogueOverrides.apply("Yes.", "نعم."))
    }
}
