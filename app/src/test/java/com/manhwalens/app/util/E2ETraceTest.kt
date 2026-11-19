package com.manhwalens.app.util

import com.manhwalens.app.util.E2ETrace.Mark
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class E2ETraceTest {

    private var now = 0L

    @Before
    fun setUp() {
        now = 0L
        E2ETrace.reset()
        E2ETrace.clock = { now }
    }

    @After
    fun tearDown() {
        E2ETrace.clock = E2ETrace.SYSTEM_CLOCK
        E2ETrace.reset()
    }

    @Test
    fun percentileUsesNearestRank() {
        val v = (1L..20L).toList()
        assertEquals(10L, E2ETrace.percentile(v, 50))
        assertEquals(19L, E2ETrace.percentile(v, 95))
        assertEquals(7L, E2ETrace.percentile(listOf(7L), 95))
        assertEquals(E2ETrace.UNSET, E2ETrace.percentile(emptyList(), 50))
    }

    @Test
    fun spansBetweenMarksAreComputed() {
        now = 1000
        val p = E2ETrace.begin("live", settleMs = 420)
        now = 1010; E2ETrace.mark(p, Mark.T1_CAPTURE_DONE)
        now = 1040; E2ETrace.mark(p, Mark.T2_PREPROCESS_DONE)
        now = 2300; E2ETrace.mark(p, Mark.T3_OCR_DONE)
        now = 2340; E2ETrace.mark(p, Mark.T4_GROUPED)
        now = 2350; E2ETrace.mark(p, Mark.T5_TRANSLATE_START)
        now = 2700; E2ETrace.mark(p, Mark.T6_FIRST_SENTENCE)
        now = 2900; E2ETrace.mark(p, Mark.T7_ALL_DISPLAYED)
        E2ETrace.finish(p)

        assertEquals(1260L, p.span(Mark.T2_PREPROCESS_DONE, Mark.T3_OCR_DONE))
        assertEquals(1700L, p.span(Mark.T0_SCAN_START, Mark.T6_FIRST_SENTENCE))
        assertEquals(1900L, p.span(Mark.T0_SCAN_START, Mark.T7_ALL_DISPLAYED))
        assertTrue(p.complete)
        val report = E2ETrace.report()
        assertTrue(report, report.contains("first sentence after stable point (T6-T0): median 1700"))
        assertTrue(report, report.contains("last frame change to first sentence (settle + T6-T0): median 2120"))
        assertTrue(report, report.contains("full screen after stable point (T7-T0): median 1900"))
        assertTrue(report, report.contains("last frame change to full display (settle + T7-T0): median 2320"))
        assertTrue(report, report.contains("settle wait (before T0): median 420"))
    }

    @Test
    fun firstStampWins() {
        val p = E2ETrace.begin("tap")
        now = 100; E2ETrace.mark(p, Mark.T6_FIRST_SENTENCE)
        now = 200; E2ETrace.mark(p, Mark.T6_FIRST_SENTENCE)
        assertEquals(100L, p.at(Mark.T6_FIRST_SENTENCE))
    }

    @Test
    fun allDisplayedStampsFirstAndLastTogether() {
        val p = E2ETrace.begin("live")
        now = 500
        E2ETrace.markAllDisplayed(p)
        assertEquals(500L, p.at(Mark.T6_FIRST_SENTENCE))
        assertEquals(500L, p.at(Mark.T7_ALL_DISPLAYED))
    }

    @Test
    fun failedAndIncompletePassesAreCountedButExcludedFromLatency() {
        val ok = E2ETrace.begin("live")
        now = 100; E2ETrace.markAllDisplayed(ok); E2ETrace.finish(ok)

        val bad = E2ETrace.begin("live")
        E2ETrace.finish(bad, failed = true)

        val empty = E2ETrace.begin("live")
        E2ETrace.finish(empty) // never displayed anything

        val report = E2ETrace.report()
        assertTrue(report, report.contains("passes=3 complete=1 incomplete=1 failed=1"))
        assertTrue(report, report.contains("first sentence after stable point (T6-T0): median 100"))
        assertFalse(ok.failed)
        assertTrue(bad.failed)
    }

    @Test
    fun modeFilterSeparatesTapFromLive() {
        val live = E2ETrace.begin("live"); now = 50; E2ETrace.markAllDisplayed(live); E2ETrace.finish(live)
        val tap = E2ETrace.begin("tap"); now = 90; E2ETrace.markAllDisplayed(tap); E2ETrace.finish(tap)
        assertTrue(E2ETrace.report("tap").contains("passes=1"))
        assertTrue(E2ETrace.report("live").contains("passes=1"))
        assertTrue(E2ETrace.report("none").contains("no passes recorded"))
    }

    @Test
    fun ringBufferKeepsTheLast200Passes() {
        repeat(250) { E2ETrace.finish(E2ETrace.begin("live")) }
        val snap = E2ETrace.snapshot()
        assertEquals(200, snap.size)
        assertEquals(51L, snap.first().id)
        assertEquals(250L, snap.last().id)
    }

    @Test
    fun finishingSamePassTwiceDoesNotDuplicateIt() {
        val pass = E2ETrace.begin("tap")
        E2ETrace.finish(pass)
        E2ETrace.finish(pass)
        assertEquals(1, E2ETrace.snapshot().size)
    }

    @Test
    fun pendingHandOffIsTakenOnce() {
        E2ETrace.beginPending("live", settleMs = 300)
        assertEquals(300L, E2ETrace.peekPending()!!.settleMs)
        val p = E2ETrace.takePending()!!
        assertEquals("live", p.mode)
        assertEquals(null, E2ETrace.takePending())
    }
}

