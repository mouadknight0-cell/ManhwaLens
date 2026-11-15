package com.manhwalens.app.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureQCTest {

    @Test
    fun looksCut_detectsIncompleteSentence() {
        assertTrue(CaptureQC.looksCut("This sentence has no ending"))
        assertTrue(CaptureQC.looksCut("What about this one"))
        assertTrue(CaptureQC.looksCut("something incomplete"))
    }

    @Test
    fun looksCut_keepsCompleteSentences() {
        assertFalse(CaptureQC.looksCut("This is complete."))
        assertFalse(CaptureQC.looksCut("Is this complete?"))
        assertFalse(CaptureQC.looksCut("Wow!"))
        assertFalse(CaptureQC.looksCut("Wait…"))
        assertFalse(CaptureQC.looksCut("نعم."))
        assertFalse(CaptureQC.looksCut("هل هذا؟"))
        assertFalse(CaptureQC.looksCut("He said \"hello.\""))
    }

    @Test
    fun looksCut_shortTextNeverCut() {
        assertFalse(CaptureQC.looksCut("Hi"))
        assertFalse(CaptureQC.looksCut("OK"))
        assertFalse(CaptureQC.looksCut("No!"))
    }

    @Test
    fun looksCut_emptyText() {
        assertFalse(CaptureQC.looksCut(""))
        assertFalse(CaptureQC.looksCut("   "))
    }
}
