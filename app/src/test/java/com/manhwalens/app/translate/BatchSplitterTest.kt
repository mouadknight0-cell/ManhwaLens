package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchSplitterTest {

    private val sources = listOf("Hello there.", "Where are you going?", "Stay behind me.")
    private val fake = FakeArabicEngine.translate

    // ---------- every strategy: clean round trip ----------

    @Test
    fun clean_everyStrategyRoundTrips() {
        for (s in SplitStrategy.values()) {
            val reply = fake(BatchSplitter.join(s, sources))
            val v = BatchSplitter.validate(s, sources, reply)
            assertTrue("${s.label}: ${v.reason}", v.ok)
            assertEquals(s.label, 3, v.segments.size)
            assertEquals(s.label, 0, v.mismatched)
            assertEquals(s.label, fake("Hello there."), v.segments[0])
            assertEquals(s.label, fake("Stay behind me."), v.segments[2])
        }
    }

    @Test
    fun join_formats() {
        val two = listOf("A b", "C d")
        assertEquals("A b\n@@@\nC d", BatchSplitter.join(SplitStrategy.AT, two))
        assertEquals("A b\nC d", BatchSplitter.join(SplitStrategy.NEWLINE, two))
        assertEquals("A b\n\nC d", BatchSplitter.join(SplitStrategy.DOUBLE_NEWLINE, two))
        assertEquals("A b | C d", BatchSplitter.join(SplitStrategy.PIPE, two))
        assertEquals("A b\n###\nC d", BatchSplitter.join(SplitStrategy.HASH, two))
        assertEquals("[1] A b\n[2] C d", BatchSplitter.join(SplitStrategy.BRACKET_NUM, two))
        assertEquals("<1> A b\n<2> C d", BatchSplitter.join(SplitStrategy.ANGLE_NUM, two))
        assertEquals("• A b\n• C d", BatchSplitter.join(SplitStrategy.BULLET, two))
    }

    @Test
    fun join_flattensInnerNewlines_soLineSeparatorsStayUnambiguous() {
        val joined = BatchSplitter.join(SplitStrategy.NEWLINE, listOf("line one\nline two", "next"))
        assertEquals("line one line two\nnext", joined)
    }

    @Test
    fun whitespaceAroundSeparatorsIsTolerated() {
        val reply = "  مرحبا بك  \n\n @@@ \n\n  إلى أين تذهب؟ \n@@@\nابق خلفي "
        val v = BatchSplitter.validate(SplitStrategy.AT, sources, reply)
        assertTrue(v.reason, v.ok)
        assertEquals("مرحبا بك", v.segments[0])
    }

    @Test
    fun numberedMarkers_acceptArabicIndicDigits() {
        val reply = "[١] مرحبا بك\n[٢] إلى أين تذهب؟\n[٣] ابق خلفي"
        assertTrue(BatchSplitter.validate(SplitStrategy.BRACKET_NUM, sources, reply).ok)
    }

    // ---------- mangled / merged / dropped / extra ----------

    @Test
    fun mangledSeparator_fails() {
        // The model "translated" the separator: @@@ became @ @ (device evidence for "@@@").
        val reply = fake(BatchSplitter.join(SplitStrategy.AT, sources)).replace("@@@", "@ @")
        val v = BatchSplitter.validate(SplitStrategy.AT, sources, reply)
        assertFalse(v.ok)
        assertTrue(v.mismatched >= 1)
        assertNull(BatchSplitter.split(SplitStrategy.AT, sources, reply))
    }

    @Test
    fun mergedSegments_fail_forEveryStrategy() {
        for (s in SplitStrategy.values()) {
            // Model merges everything into one sentence.
            val reply = fake(sources.joinToString(" "))
            val v = BatchSplitter.validate(s, sources, reply)
            assertFalse(s.label, v.ok)
            assertEquals(s.label, 2, v.mismatched) // 1 segment instead of 3
        }
    }

    @Test
    fun droppedSegment_fails() {
        val reply = fake(BatchSplitter.join(SplitStrategy.HASH, sources.take(2)))
        val v = BatchSplitter.validate(SplitStrategy.HASH, sources, reply)
        assertFalse(v.ok)
        assertEquals(1, v.mismatched)
        assertTrue(v.reason.contains("count"))
    }

    @Test
    fun extraSeparator_fails() {
        val reply = fake(BatchSplitter.join(SplitStrategy.PIPE, sources)) + " | " + fake("extra words")
        val v = BatchSplitter.validate(SplitStrategy.PIPE, sources, reply)
        assertFalse(v.ok)
        assertEquals(1, v.mismatched)
    }

    @Test
    fun trailingSeparator_countsAsExtraEmptySegment() {
        val reply = fake(BatchSplitter.join(SplitStrategy.AT, sources)) + "\n@@@\n"
        // trim() removes the trailing newline; the dangling @@@ still yields an empty 4th part
        assertFalse(BatchSplitter.validate(SplitStrategy.AT, sources, reply + "x").ok)
    }

    @Test
    fun numberedMarkersOutOfOrder_fail() {
        val reply = "[2] مرحبا بك\n[1] إلى أين تذهب؟\n[3] ابق خلفي"
        val v = BatchSplitter.validate(SplitStrategy.BRACKET_NUM, sources, reply)
        assertFalse(v.ok)
        assertTrue(v.reason.contains("order"))
    }

    @Test
    fun textBeforeFirstMarker_isAnExtraSegment() {
        val reply = "ترجمة: <1> مرحبا بك\n<2> إلى أين تذهب؟\n<3> ابق خلفي"
        assertFalse(BatchSplitter.validate(SplitStrategy.ANGLE_NUM, sources, reply).ok)
    }

    // ---------- validation rules ----------

    @Test
    fun rule_emptySegment() {
        val v = BatchSplitter.validate(SplitStrategy.NEWLINE, sources, "مرحبا بك\n\nابق خلفي")
        assertFalse(v.ok)
        assertEquals(1, v.mismatched)
        assertTrue(v.reason.contains("empty"))
    }

    @Test
    fun rule_arabicLettersRequired() {
        // Middle segment came back untranslated.
        val v = BatchSplitter.validate(SplitStrategy.NEWLINE, sources, "مرحبا بك\nSomething else in English\nابق خلفي")
        assertFalse(v.ok)
        assertTrue(v.reason.contains("Arabic"))
    }

    /** Device benchmark v0.9.10: "KRAK" came back as "KRAK" and failed four good separators. */
    @Test
    fun rule_untranslatedEcho_isNotAMismatch() {
        val src = listOf("Hello there.", "KRAK", "Stay behind me.")
        val v = BatchSplitter.validate(SplitStrategy.HASH, src, "مرحبا بك\n###\nKrak!\n###\nابق خلفي")
        assertTrue(v.reason, v.ok)
        assertTrue(BatchSplitter.isEcho("KRAK", " krak. "))
        assertFalse(BatchSplitter.isEcho("KRAK", "KRAKEN"))
        assertFalse(BatchSplitter.isEcho("...", "..."))
    }

    @Test
    fun rule_arabicNotRequired_whenSourceHasNoLetters() {
        assertNull(BatchSplitter.segmentProblem("...?!", "...؟!", "@"))
        assertNull(BatchSplitter.segmentProblem("100%", "100%", "@"))
    }

    @Test
    fun rule_lengthRatio() {
        // too short: 2 chars for a 40-char source (< 0.3x)
        assertNotNull(BatchSplitter.segmentProblem("This is a rather long sentence, isn't it", "لا", ""))
        // too long: > 4x
        assertNotNull(BatchSplitter.segmentProblem("Hello there", "ا".repeat(50), ""))
        // inside the window
        assertNull(BatchSplitter.segmentProblem("Hello there", "مرحبا بك هناك", ""))
        // tiny sources are judged against a 4-char base
        assertNull(BatchSplitter.segmentProblem("No", "لا أريد ذلك", ""))
        assertNotNull(BatchSplitter.segmentProblem("No", "ا".repeat(17), ""))
    }

    @Test
    fun rule_markerLeftInside() {
        // Count is right but a stray marker survived inside a segment.
        val v = BatchSplitter.validate(SplitStrategy.HASH, sources, "مرحبا بك\n###\nإلى أين # تذهب؟\n###\nابق خلفي")
        assertFalse(v.ok)
        assertEquals(1, v.mismatched)
        assertTrue(v.reason.contains("marker"))
    }

    @Test
    fun rule_markerAllowed_whenSourceContainsIt() {
        assertNull(BatchSplitter.segmentProblem("Email me @ noon", "راسلني @ الظهر", "@"))
    }

    @Test
    fun doubleNewline_singleNewlineInsideSegment_isMarkerLeft() {
        val v = BatchSplitter.validate(SplitStrategy.DOUBLE_NEWLINE, sources, "مرحبا\nبك\n\nإلى أين تذهب؟\n\nابق خلفي")
        assertFalse(v.ok)
    }

    @Test
    fun strategyIds_roundTrip() {
        for (s in SplitStrategy.values()) assertEquals(s, SplitStrategy.fromId(s.id))
        assertNull(SplitStrategy.fromId("parallel"))
        assertNull(SplitStrategy.fromId(null))
    }
}
