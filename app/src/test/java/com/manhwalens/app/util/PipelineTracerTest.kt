package com.manhwalens.app.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PipelineTracerTest {

    @After
    fun tearDown() = PipelineTracer.reset()

    @Test
    fun record_thenSnapshot_returnsAllInOrder() {
        PipelineTracer.reset()
        PipelineTracer.record(PipelineTracer.Stage.TAP, StageTrace.Status.OK, 0, "tap received")
        PipelineTracer.record(PipelineTracer.Stage.OCR, StageTrace.Status.FAILED, 12, "boom")
        val snap = PipelineTracer.snapshot()
        assertEquals(2, snap.size)
        assertEquals("TAP", snap[0].stage)
        assertEquals("OCR", snap[1].stage)
        assertEquals(StageTrace.Status.FAILED, snap[1].status)
    }

    @Test
    fun report_neverContainsApiKeys() {
        PipelineTracer.reset()
        // Simulate a failure message that somehow carried a key (defense in depth).
        PipelineTracer.record(
            PipelineTracer.Stage.TRANSLATE,
            StageTrace.Status.FAILED,
            5,
            "HTTP 401 — key=AIzaSyABCDEFGHIJKL1234567890abcdefghij"
        )
        val report = PipelineTracer.report()
        assertFalse(report.contains("AIzaSyABCDEFGHIJKL1234567890abcdefghij"))
        assertTrue(report.contains("[redacted]"))
    }

    @Test
    fun line_formatIsStable() {
        val t = StageTrace("OCR", StageTrace.Status.OK, 87, "2 blocks")
        assertEquals("OK   OCR — 87 ms — 2 blocks", t.line())
        assertEquals("FAIL  TRANSLATE — 9 ms — x", StageTrace("TRANSLATE", StageTrace.Status.FAILED, 9, "x").line())
        assertEquals("SKIP  OVERLAY — 0 ms — x", StageTrace("OVERLAY", StageTrace.Status.SKIPPED, 0, "x").line())
    }

    @Test
    fun reset_clearsPriorTap() {
        PipelineTracer.reset()
        PipelineTracer.record(PipelineTracer.Stage.TAP, StageTrace.Status.OK, 0, "tap")
        PipelineTracer.reset()
        assertEquals(0, PipelineTracer.snapshot().size)
    }
}
