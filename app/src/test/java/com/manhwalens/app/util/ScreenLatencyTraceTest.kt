package com.manhwalens.app.util

import org.junit.Assert.*
import org.junit.Test

class ScreenLatencyTraceTest {
    @Test fun includesQueueWaitAndDoesNotFinishUntilAllDistinctRegionsDraw() {
        var now = 150L
        val lines = mutableListOf<String>()
        val trace = ScreenLatencyTrace(1, { now }, 100, lines::add)
        trace.expect(listOf(7, 8, 8))
        trace.mark(ScreenLatencyTrace.Event.T1)
        now = 200
        trace.drawn(7)
        trace.drawn(7)
        trace.drawn(999)
        assertFalse(trace.snapshot().containsKey(ScreenLatencyTrace.Event.T7))
        now = 300
        trace.drawn(8)
        val times = trace.snapshot()
        assertEquals(100L, times[ScreenLatencyTrace.Event.T0])
        assertEquals(200L, times[ScreenLatencyTrace.Event.T6])
        assertEquals(300L, times[ScreenLatencyTrace.Event.T7])
        assertEquals(2, lines.count { "event=drawn" in it })
    }

    @Test fun emptyOrFailedScreenIsNotASuccessAndMilestonesAreWriteOnce() {
        var now = 10L
        val trace = ScreenLatencyTrace(2, { now }) { }
        trace.expect(emptyList())
        trace.mark(ScreenLatencyTrace.Event.T2)
        now = 90
        trace.mark(ScreenLatencyTrace.Event.T2)
        trace.drawn(1)
        assertEquals(10L, trace.snapshot()[ScreenLatencyTrace.Event.T2])
        assertFalse(trace.snapshot().containsKey(ScreenLatencyTrace.Event.T6))
        assertFalse(trace.snapshot().containsKey(ScreenLatencyTrace.Event.T7))
    }
}
