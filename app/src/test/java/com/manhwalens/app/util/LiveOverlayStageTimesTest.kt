package com.manhwalens.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveOverlayStageTimesTest {
    @Test
    fun splitsFirstOutputIntoMonotonicStageDurations() {
        val times = LiveOverlayStageTimes.measure(
            queuedAtElapsedMs = 1_000L,
            callbackStartedAtElapsedMs = 1_200L,
            patchBuildStartedAtElapsedMs = 1_215L,
            patchBuildFinishedAtElapsedMs = 1_680L,
            overlayApplyStartedAtElapsedMs = 1_710L,
            outputAppliedAtElapsedMs = 1_770L
        )

        assertEquals(200L, times.mainThreadQueueMs)
        assertEquals(15L, times.callbackPreparationMs)
        assertEquals(465L, times.patchBuildMs)
        assertEquals(30L, times.overlayHandoffMs)
        assertEquals(60L, times.overlayApplyMs)
    }

    @Test
    fun invalidClockOrderIsReportedAsUnavailableInsteadOfNegativeTime() {
        val times = LiveOverlayStageTimes.measure(
            queuedAtElapsedMs = 1_200L,
            callbackStartedAtElapsedMs = 1_100L,
            patchBuildStartedAtElapsedMs = 1_150L,
            patchBuildFinishedAtElapsedMs = 1_140L,
            overlayApplyStartedAtElapsedMs = 1_160L,
            outputAppliedAtElapsedMs = 1_170L
        )

        assertNull(times.mainThreadQueueMs)
        assertEquals(50L, times.callbackPreparationMs)
        assertNull(times.patchBuildMs)
        assertEquals(20L, times.overlayHandoffMs)
        assertEquals(10L, times.overlayApplyMs)
    }
}
