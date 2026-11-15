package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class BubbleTextRulesTest {

    @Test
    fun wrappedWordsAreHealed() {
        assertEquals("continue", BubbleTextRules.normalize("con-\ntinue"))
        assertEquals("someone", BubbleTextRules.normalize("some-one"))
    }

    @Test
    fun sentenceEndKeepsItsMark_andFragmentsGlue() {
        assertEquals("Stop! Do not move.", BubbleTextRules.oneLine("Stop!\nDo not move."))
        assertEquals("I said no because it hurts.", BubbleTextRules.oneLine("I said no\nbecause it hurts."))
        assertEquals("A, B and C", BubbleTextRules.oneLine("A,\nB and C"))
    }

    @Test
    fun emptyAndSingleLineAreStable() {
        assertEquals("", BubbleTextRules.oneLine("  "))
        assertEquals("Hello", BubbleTextRules.oneLine("Hello"))
        assertEquals(2, BubbleTextRules.lineCount("a\nb"))
    }
}
