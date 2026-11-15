package com.manhwalens.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveGatesTest {

    private fun input(
        liveMode: Boolean = true,
        projectionReady: Boolean = true,
        screenOff: Boolean = false,
        screenInteractive: Boolean = true,
        appVisibleCounter: Int = 0,
        appActuallyStarted: Int = 0,
        standby: Boolean = false,
        standbyEnteredAtMs: Long = -1L,
        lastChangeAtMs: Long = -1L,
        foregroundAllowed: Boolean = true,
        foregroundDetail: String = "ok",
        foregroundPackage: String? = null,
        nowMs: Long = 10_000L
    ) = LiveGates.Input(
        liveMode = liveMode,
        projectionReady = projectionReady,
        screenOff = screenOff,
        screenInteractive = screenInteractive,
        appVisibleCounter = appVisibleCounter,
        appActuallyStarted = appActuallyStarted,
        standby = standby,
        standbyEnteredAtMs = standbyEnteredAtMs,
        lastChangeAtMs = lastChangeAtMs,
        foregroundAllowed = foregroundAllowed,
        foregroundDetail = foregroundDetail,
        foregroundPackage = foregroundPackage,
        captureInFlight = false,
        captureStartedAtMs = 0L,
        liveJobRunning = false,
        liveJobStartedAtMs = 0L,
        nowMs = nowMs
    )

    @Test
    fun ownAppForeground_pausesWithAppVisible() {
        val d = LiveGates.evaluate(input(foregroundPackage = "com.manhwalens.app"))
        assertEquals(LiveGates.Outcome.PAUSE, d.outcome)
        assertEquals(LiveGates.GATE_APP_VISIBLE, d.gate?.name)
        assertTrue(d.gate!!.action.contains("own app visible"))
        assertTrue(d.gate!!.value.contains("com.manhwalens.app"))
    }

    @Test
    fun ownAppForeground_caseInsensitive() {
        val d = LiveGates.evaluate(input(foregroundPackage = "COM.MANHWALENS.APP"))
        assertEquals(LiveGates.Outcome.PAUSE, d.outcome)
        assertEquals(LiveGates.GATE_APP_VISIBLE, d.gate?.name)
    }

    @Test
    fun otherAppForeground_notBlockedByOwnAppCheck() {
        val d = LiveGates.evaluate(input(foregroundPackage = "com.brave.browser"))
        // Not blocked by own-app check (may be blocked by other gates if configured)
        assertFalse(d.gate?.name == LiveGates.GATE_APP_VISIBLE && d.gate!!.value.contains("own app visible"))
    }

    @Test
    fun nullForegroundPackage_notBlocked() {
        val d = LiveGates.evaluate(input(foregroundPackage = null))
        assertEquals(LiveGates.Outcome.RUN, d.outcome)
    }

    @Test
    fun ownAppForeground_takesPrecedenceOverStandby() {
        val d = LiveGates.evaluate(
            input(
                foregroundPackage = "com.manhwalens.app",
                standby = true,
                standbyEnteredAtMs = 5_000L,
                lastChangeAtMs = 2_000L
            )
        )
        assertEquals(LiveGates.Outcome.PAUSE, d.outcome)
        assertEquals(LiveGates.GATE_APP_VISIBLE, d.gate?.name)
    }
}
