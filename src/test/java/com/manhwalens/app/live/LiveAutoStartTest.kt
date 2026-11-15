package com.manhwalens.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveAutoStartTest {

    @Test
    fun loopNeverStartsWithoutCaptureSession() {
        // App launch: no MediaProjection yet — the live loop must stay dead.
        assertFalse(LiveAutoStart.shouldStartLoop(projectionReady = false))
        assertFalse(LiveAutoStart.shouldRestoreLiveSetting(projectionReady = false, liveMode = true, optOut = false))
    }

    @Test
    fun loopMayStartOnceProjectionIsArmed() {
        assertTrue(LiveAutoStart.shouldStartLoop(projectionReady = true))
    }

    @Test
    fun restoresLiveOnlyWhenEnabledAndNotOptedOut() {
        assertFalse(LiveAutoStart.shouldRestoreLiveSetting(true, liveMode = false, optOut = false))
        assertFalse(LiveAutoStart.shouldRestoreLiveSetting(true, liveMode = true, optOut = true))
        assertTrue(LiveAutoStart.shouldRestoreLiveSetting(true, liveMode = true, optOut = false))
    }

    @Test
    fun optOutAlwaysWinsEvenWithProjection() {
        assertFalse(LiveAutoStart.shouldRestoreLiveSetting(true, liveMode = true, optOut = true))
    }

    // ---------------------------------------------------------------------
    // Part B / Part E (5) — the tapAction table: projection × liveMode × tapMode
    // ---------------------------------------------------------------------

    @Test
    fun tapActionTable() {
        val expected = listOf(
            // projectionReady, liveMode, tapMode                     → action
            Triple(false, false, LiveAutoStart.TapMode.MENU) to LiveAutoStart.TapAction.CONSENT,
            Triple(false, true, LiveAutoStart.TapMode.MENU) to LiveAutoStart.TapAction.CONSENT,
            Triple(false, false, LiveAutoStart.TapMode.TRANSLATE_NOW) to LiveAutoStart.TapAction.CONSENT,
            Triple(false, true, LiveAutoStart.TapMode.TRANSLATE_NOW) to LiveAutoStart.TapAction.CONSENT,
            Triple(true, false, LiveAutoStart.TapMode.MENU) to LiveAutoStart.TapAction.MENU,
            Triple(true, true, LiveAutoStart.TapMode.MENU) to LiveAutoStart.TapAction.MENU,
            Triple(true, false, LiveAutoStart.TapMode.TRANSLATE_NOW) to LiveAutoStart.TapAction.TRANSLATE_NOW,
            Triple(true, true, LiveAutoStart.TapMode.TRANSLATE_NOW) to LiveAutoStart.TapAction.TRANSLATE_NOW
        )
        for ((input, want) in expected) {
            val (projectionReady, liveMode, tapMode) = input
            val got = LiveAutoStart.tapAction(projectionReady, liveMode, tapMode)
            assertEquals(
                "tapAction(projection=$projectionReady, liveMode=$liveMode, tapMode=${tapMode.wire})",
                want, got
            )
        }
    }

    @Test
    fun consentAlwaysComesFirst_v093ReadLiveModeBeforeTheProjection() {
        // The exact v0.9.3 bug: liveMode persisted true, no MediaProjection → the tap went
        // to the live handler and nothing happened. Now it must ask for consent.
        assertEquals(
            LiveAutoStart.TapAction.CONSENT,
            LiveAutoStart.tapAction(projectionReady = false, liveMode = true, tapMode = LiveAutoStart.TapMode.MENU)
        )
        assertTrue(LiveAutoStart.needsConsentBeforeLiveEnable(projectionReady = false))
        assertFalse(LiveAutoStart.needsConsentBeforeLiveEnable(projectionReady = true))
    }

    @Test
    fun liveModeNeverChangesTheTapOutcome() {
        for (projection in listOf(true, false)) {
            for (mode in LiveAutoStart.TapMode.entries) {
                assertEquals(
                    LiveAutoStart.tapAction(projection, liveMode = false, tapMode = mode),
                    LiveAutoStart.tapAction(projection, liveMode = true, tapMode = mode)
                )
            }
        }
    }

    @Test
    fun persistedLiveWithoutASessionShowsNoSession() {
        assertTrue(LiveAutoStart.showsNoSession(projectionReady = false, liveMode = true))
        assertFalse(LiveAutoStart.showsNoSession(projectionReady = false, liveMode = false))
        assertFalse(LiveAutoStart.showsNoSession(projectionReady = true, liveMode = true))
    }

    @Test
    fun tapModeWireValuesMatchThePersistedSetting() {
        assertEquals(LiveAutoStart.TapMode.MENU, LiveAutoStart.TapMode.fromWire("menu"))
        assertEquals(LiveAutoStart.TapMode.TRANSLATE_NOW, LiveAutoStart.TapMode.fromWire("translate"))
        assertEquals("unknown/corrupt values fall back to the menu", LiveAutoStart.TapMode.MENU, LiveAutoStart.TapMode.fromWire("nonsense"))
        assertEquals(LiveAutoStart.TapMode.MENU, LiveAutoStart.TapMode.fromWire(null))
        assertEquals("menu", LiveAutoStart.TapMode.MENU.wire)
        assertEquals("translate", LiveAutoStart.TapMode.TRANSLATE_NOW.wire)
    }
}
