package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 — evidence, not spelling.
 *
 * The persistent memory only stores a name as *confirmed* when the script is known with
 * confidence. That makes this classifier a safety boundary: a bare Latin surname must never be
 * enough to invent an Arabic spelling, because "Wang", "Kim", "Lee", "Lin" and "Han" are ordinary
 * English words, several romanizations and surnames in three languages at once.
 */
class NameScriptClassifyTest {

    @Test
    fun `a bare surname is not evidence of a script`() {
        for (surname in listOf("Wang", "Kim", "Lee", "Lin", "Han")) {
            val evidence = NameScript.classify(surname)
            assertEquals("bare surname classified: $surname", NameScript.UNKNOWN, evidence.script)
            assertFalse("bare surname looked confident: $surname", evidence.isConfident)
            assertTrue("confidence too high for $surname", evidence.confidence < 0.6f)
        }
    }

    @Test
    fun `an empty or meaningless token is unknown`() {
        assertEquals(NameScript.UNKNOWN, NameScript.classify("").script)
        assertEquals(NameScript.UNKNOWN, NameScript.classify("   ").script)
        assertTrue(NameScript.classify("").confidence == 0f)
    }

    @Test
    fun `a hyphenated syllable block is a confident Korean name`() {
        val evidence = NameScript.classify("Ji-woo")
        assertEquals(NameScript.KOREAN, evidence.script)
        assertTrue(evidence.isConfident)
        assertTrue(evidence.reasons.isNotEmpty())
    }

    @Test
    fun `a hyphenated full name is Korean with a surname`() {
        assertEquals(NameScript.KOREAN, NameScript.classify("Han Ji-woo").script)
    }

    @Test
    fun `pinyin-only letter pairs identify a Chinese name`() {
        val evidence = NameScript.classify("Wei Wuxian")
        assertEquals(NameScript.CHINESE, evidence.script)
        assertTrue(evidence.isConfident)
    }

    @Test
    fun `classification never throws on odd input`() {
        for (odd in listOf("O'Brien", "'", "A-", "-B", "x", "!!", "Ж")) {
            NameScript.classify(odd) // must not throw
        }
    }
}
