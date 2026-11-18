package com.manhwalens.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveOverlayStageTimesTest {
    @Test
    fun splitsWorkerAndMainThreadIntervals() {
        val t = LiveOverlayStageTimes.measure(1_000L, 3_000L, 3_050L, 3_051L, 3_070L, 3_110L)
        assertEquals(2_000L, t.patchBuildMs)
        assertEquals(50L, t.workerHandoffMs)
        assertEquals(1L, t.mainThreadQueueMs)
        assertEquals(19L, t.callbackPreparationMs)
        assertEquals(40L, t.overlayApplyMs)
    }

    @Test
    fun invalidClockOrderIsUnavailableRatherThanNegative() {
        val t = LiveOverlayStageTimes.measure(1_000L, 1_200L, 1_190L, 1_180L, 1_170L, 1_160L)
        assertEquals(200L, t.patchBuildMs)
        assertNull(t.workerHandoffMs)
        assertNull(t.mainThreadQueueMs)
        assertNull(t.callbackPreparationMs)
        assertNull(t.overlayApplyMs)
    }
}
