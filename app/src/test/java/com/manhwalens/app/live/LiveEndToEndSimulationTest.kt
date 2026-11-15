package com.manhwalens.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.manhwalens.app.util.AppVisibility

/**
 * Part 4 — pure-JVM end-to-end simulation of the v0.9.5 Live path.
 *
 * Exercises the new pure classes together — LiveGates gate chain, LiveGateLog,
 * ForegroundAppPolicy, LiveAutoStart, LiveSelfCheck (stages + overall + report),
 * LiveLogRing and AppVisibility — plus a mini end-to-end scenario that wires the
 * gate chain into a driver tick and a self-check verdict. No Android types,
 * no Robolectric, no device.
 */
class LiveEndToEndSimulationTest {

    // -----------------------------------------------------------------------
    // 1. LiveGates gate chain — evaluate()
    // -----------------------------------------------------------------------

    private fun gatesInput(
        liveMode: Boolean = true,
        projectionReady: Boolean = true,
        screenOff: Boolean = false,
        screenInteractive: Boolean = true,
        appVisibleCounter: Int = 0,
        appActuallyStarted: Int = 0,
        standby: Boolean = false,
        standbyEnteredAtMs: Long = -1L,
        lastChangeAtMs: Long = 10_000L,
        foregroundAllowed: Boolean = true,
        foregroundDetail: String = "ok",
        captureInFlight: Boolean = false,
        captureStartedAtMs: Long = -1L,
        liveJobRunning: Boolean = false,
        liveJobStartedAtMs: Long = -1L,
        nowMs: Long = 100_000L
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
        foregroundPackage = null,
        captureInFlight = captureInFlight,
        captureStartedAtMs = captureStartedAtMs,
        liveJobRunning = liveJobRunning,
        liveJobStartedAtMs = liveJobStartedAtMs,
        nowMs = nowMs
    )

    @Test
    fun liveGates_allClear_runs() {
        val d = LiveGates.evaluate(gatesInput())
        assertEquals(LiveGates.Outcome.RUN, d.outcome)
        assertNull(d.gate)
        assertTrue(d.heals.isEmpty())
    }

    @Test
    fun liveGates_liveModeOff_pauses() {
        val d = LiveGates.evaluate(gatesInput(liveMode = false))
        assertEquals(LiveGates.Outcome.PAUSE, d.outcome)
        assertEquals(LiveGates.GATE_LIVE_MODE, d.gate?.name)
    }

    @Test
    fun liveGates_noProjection_pauses() {
        val d = LiveGates.evaluate(gatesInput(projectionReady = false))
        assertEquals(LiveGates.Outcome.PAUSE, d.outcome)
        assertEquals(LiveGates.GATE_PROJECTION, d.gate?.name)
    }

    @Test
    fun liveGates_screenOffPaused_healsWhenInteractive() {
        // screenOff flag set but the display is interactive → heal, do not pause
        val d = LiveGates.evaluate(gatesInput(screenOff = true, screenInteractive = true))
        assertEquals(LiveGates.Outcome.RUN, d.outcome)
        assertEquals(1, d.heals.size)
        assertEquals(LiveGates.GATE_SCREEN_OFF, d.heals[0].flag)
    }

    @Test
    fun liveGates_screenOffReallyOff_pauses() {
        val d = LiveGates.evaluate(gatesInput(screenOff = true, screenInteractive = false))
        assertEquals(LiveGates.Outcome.PAUSE, d.outcome)
        assertEquals(LiveGates.GATE_SCREEN_OFF, d.gate?.name)
    }

    @Test
    fun liveGates_appVisibleWithNoActivity_heals() {
        val d = LiveGates.evaluate(gatesInput(appVisibleCounter = 3, appActuallyStarted = 0))
        assertEquals(LiveGates.Outcome.RUN, d.outcome)
        assertEquals(1, d.heals.size)
        assertEquals(LiveGates.GATE_APP_VISIBLE, d.heals[0].flag)
    }

    @Test
    fun liveGates_appVisibleReallyVisible_pauses() {
        val d = LiveGates.evaluate(gatesInput(appVisibleCounter = 1, appActuallyStarted = 1))
        assertEquals(LiveGates.Outcome.PAUSE, d.outcome)
        assertEquals(LiveGates.GATE_APP_VISIBLE, d.gate?.name)
    }

    @Test
    fun liveGates_standbyWithMissedChange_heals() {
        // a frame changed while standby was entered → heal and resume
        val d = LiveGates.evaluate(
            gatesInput(standby = true, standbyEnteredAtMs = 50_000L, lastChangeAtMs = 60_000L, nowMs = 100_000L)
        )
        assertEquals(LiveGates.Outcome.RUN, d.outcome)
        assertEquals(1, d.heals.size)
        assertEquals(LiveGates.GATE_STANDBY, d.heals[0].flag)
    }

    @Test
    fun liveGates_standbyNoChange_standbyOutcome() {
        val d = LiveGates.evaluate(
            gatesInput(standby = true, standbyEnteredAtMs = 50_000L, lastChangeAtMs = 40_000L, nowMs = 100_000L)
        )
        assertEquals(LiveGates.Outcome.STANDBY, d.outcome)
        assertEquals(LiveGates.GATE_STANDBY, d.gate?.name)
    }

    @Test
    fun liveGates_foregroundBlocked_pauses() {
        val d = LiveGates.evaluate(
            gatesInput(foregroundAllowed = false, foregroundDetail = "foreground=com.brave not in list(...)")
        )
        assertEquals(LiveGates.Outcome.PAUSE, d.outcome)
        assertEquals(LiveGates.GATE_FOREGROUND, d.gate?.name)
    }

    @Test
    fun liveGates_captureInFlightFresh_waits() {
        val d = LiveGates.evaluate(
            gatesInput(captureInFlight = true, captureStartedAtMs = 99_000L, nowMs = 100_000L)
        )
        assertEquals(LiveGates.Outcome.WAIT, d.outcome)
        assertEquals(LiveGates.GATE_CAPTURE_IN_FLIGHT, d.gate?.name)
    }

    @Test
    fun liveGates_captureInFlightStuck_heals() {
        // 90 s old capture → stuck (> 30 s) → heal
        val d = LiveGates.evaluate(
            gatesInput(captureInFlight = true, captureStartedAtMs = 10_000L, nowMs = 100_000L)
        )
        assertEquals(LiveGates.Outcome.RUN, d.outcome)
        assertEquals(1, d.heals.size)
        assertEquals(LiveGates.GATE_CAPTURE_IN_FLIGHT, d.heals[0].flag)
    }

    @Test
    fun liveGates_liveJobFresh_waits() {
        val d = LiveGates.evaluate(
            gatesInput(liveJobRunning = true, liveJobStartedAtMs = 99_000L, nowMs = 100_000L)
        )
        assertEquals(LiveGates.Outcome.WAIT, d.outcome)
        assertEquals(LiveGates.GATE_LIVE_JOB, d.gate?.name)
    }

    @Test
    fun liveGates_liveJobStuck_heals() {
        // 30 s old job → stuck (> 20 s) → heal
        val d = LiveGates.evaluate(
            gatesInput(liveJobRunning = true, liveJobStartedAtMs = 70_000L, nowMs = 100_000L)
        )
        assertEquals(LiveGates.Outcome.RUN, d.outcome)
        assertEquals(1, d.heals.size)
        assertEquals(LiveGates.GATE_LIVE_JOB, d.heals[0].flag)
    }

    @Test
    fun liveGates_firstBlockingGateWins() {
        // liveMode off AND no projection → liveMode is checked first
        val d = LiveGates.evaluate(gatesInput(liveMode = false, projectionReady = false))
        assertEquals(LiveGates.GATE_LIVE_MODE, d.gate?.name)
    }

    // -----------------------------------------------------------------------
    // 2. LiveGateLog — lastGate always updated, rate-limited, summary
    // -----------------------------------------------------------------------

    @Test
    fun liveGateLog_lastGateAlwaysUpdated() {
        val log = LiveGateLog(intervalMs = 1_000_000) // never rate-limits in this test
        log.record(LiveGate(LiveGates.GATE_LIVE_MODE, "false", "paused"))
        assertEquals(LiveGates.GATE_LIVE_MODE, log.lastGate?.name)
        log.record(null) // tick ran through
        assertNull(log.lastGate)
    }

    @Test
    fun liveGateLog_hitsAndSummary() {
        val log = LiveGateLog(intervalMs = 1)
        log.record(LiveGate(LiveGates.GATE_PROJECTION, "none", "no-session"))
        log.record(LiveGate(LiveGates.GATE_PROJECTION, "none", "no-session"))
        log.record(LiveGate(LiveGates.GATE_LIVE_MODE, "false", "paused"))
        assertEquals(2, log.hitCount(LiveGates.GATE_PROJECTION))
        assertEquals(1, log.hitCount(LiveGates.GATE_LIVE_MODE))
        assertTrue(log.summary().contains("projection=2"))
        assertTrue(log.summary().contains("liveMode=1"))
    }

    // -----------------------------------------------------------------------
    // 3. LiveGates.severity
    // -----------------------------------------------------------------------

    @Test
    fun liveGates_severity_table() {
        assertEquals(LiveGates.Severity.OK, LiveGates.severity(null))
        assertEquals(LiveGates.Severity.OK, LiveGates.severity(LiveGates.GATE_NONE))
        assertEquals(LiveGates.Severity.BLOCKED, LiveGates.severity(LiveGates.GATE_LIVE_MODE))
        assertEquals(LiveGates.Severity.BLOCKED, LiveGates.severity(LiveGates.GATE_PROJECTION))
        assertEquals(LiveGates.Severity.BLOCKED, LiveGates.severity(LiveGates.GATE_APP_VISIBLE))
        assertEquals(LiveGates.Severity.BLOCKED, LiveGates.severity(LiveGates.GATE_FOREGROUND))
        assertEquals(LiveGates.Severity.WAITING, LiveGates.severity(LiveGates.GATE_SCREEN_OFF))
        assertEquals(LiveGates.Severity.WAITING, LiveGates.severity(LiveGates.GATE_STANDBY))
        assertEquals(LiveGates.Severity.WAITING, LiveGates.severity(LiveGates.GATE_CAPTURE_IN_FLIGHT))
        assertEquals(LiveGates.Severity.WAITING, LiveGates.severity(LiveGates.GATE_LIVE_JOB))
        assertEquals(LiveGates.Severity.BLOCKED, LiveGates.severity("unknown"))
    }

    // -----------------------------------------------------------------------
    // 4. ForegroundAppPolicy.decide
    // -----------------------------------------------------------------------

    @Test
    fun foregroundPolicy_emptyList_allows() {
        assertTrue(ForegroundAppPolicy.decide("", true, "com.brave", 1000L).allowed)
    }

    @Test
    fun foregroundPolicy_noUsageAccess_allows() {
        assertTrue(ForegroundAppPolicy.decide("com.brave", false, "com.other", 500L).allowed)
    }

    @Test
    fun foregroundPolicy_noEvent_allows() {
        assertTrue(ForegroundAppPolicy.decide("com.brave", true, null, -1L).allowed)
    }

    @Test
    fun foregroundPolicy_staleEvent_allows() {
        assertTrue(ForegroundAppPolicy.decide("com.brave", true, "com.other", 15_000L).allowed)
    }

    @Test
    fun foregroundPolicy_listed_allows() {
        assertTrue(ForegroundAppPolicy.decide("com.brave", true, "com.brave", 500L).allowed)
    }

    @Test
    fun foregroundPolicy_unlisted_blocks() {
        val d = ForegroundAppPolicy.decide("com.brave", true, "com.other", 500L)
        assertFalse(d.allowed)
        assertTrue(d.reason.contains("foreground=com.other not in list"))
    }

    // -----------------------------------------------------------------------
    // 5. LiveAutoStart
    // -----------------------------------------------------------------------

    @Test
    fun autoStart_tapAction_consentWhenNoSession() {
        assertEquals(
            LiveAutoStart.TapAction.CONSENT,
            LiveAutoStart.tapAction(false, true, LiveAutoStart.TapMode.MENU)
        )
        assertEquals(
            LiveAutoStart.TapAction.CONSENT,
            LiveAutoStart.tapAction(false, false, LiveAutoStart.TapMode.TRANSLATE_NOW)
        )
    }

    @Test
    fun autoStart_tapAction_menuAndTranslateWhenSession() {
        assertEquals(
            LiveAutoStart.TapAction.MENU,
            LiveAutoStart.tapAction(true, false, LiveAutoStart.TapMode.MENU)
        )
        assertEquals(
            LiveAutoStart.TapAction.TRANSLATE_NOW,
            LiveAutoStart.tapAction(true, false, LiveAutoStart.TapMode.TRANSLATE_NOW)
        )
    }

    @Test
    fun autoStart_pendingLiveEnable_defaultsOn() {
        // tap with no explicit request → enabled unless opted out
        assertTrue(LiveAutoStart.pendingLiveEnableOnTap(liveMode = false, liveOptOut = false))
        assertFalse(LiveAutoStart.pendingLiveEnableOnTap(liveMode = false, liveOptOut = true))
        assertTrue(LiveAutoStart.pendingLiveEnableOnTap(liveMode = false, liveOptOut = true, requestedLiveEnable = true))
    }

    @Test
    fun autoStart_afterConsent_enableLive() {
        val p = LiveAutoStart.afterConsent(
            tapMode = LiveAutoStart.TapMode.MENU,
            requestedLiveEnable = true,
            liveMode = false,
            liveOptOut = false
        )
        assertTrue(p.enableLive)
        assertTrue(p.openMenu)
        assertFalse(p.translateNow)
    }

    @Test
    fun autoStart_afterConsent_translateNowWhenNotEnabling() {
        val p = LiveAutoStart.afterConsent(
            tapMode = LiveAutoStart.TapMode.TRANSLATE_NOW,
            requestedLiveEnable = false,
            liveMode = false,
            liveOptOut = true
        )
        assertFalse(p.enableLive)
        assertFalse(p.openMenu)
        assertTrue(p.translateNow)
    }

    @Test
    fun autoStart_showsNoSession() {
        assertTrue(LiveAutoStart.showsNoSession(projectionReady = false, liveMode = true))
        assertFalse(LiveAutoStart.showsNoSession(projectionReady = true, liveMode = true))
        assertFalse(LiveAutoStart.showsNoSession(projectionReady = false, liveMode = false))
    }

    // -----------------------------------------------------------------------
    // 6. LiveSelfCheck stages + overall
    // -----------------------------------------------------------------------

    private fun selfCheckInput(
        overlayPermission: Boolean = true,
        liveMode: Boolean = true,
        projectionActive: Boolean = true,
        sessionExists: Boolean = true,
        sessionAgeMs: Long = 5_000L,
        firstFrameMs: Long = 400L,
        framesPerSecond: Float = 2.5f,
        framesTotal: Long = 10L,
        idleTicks: Long = 3L,
        frameOrigin: String = "stash",
        loopActive: Boolean = true,
        lastGate: String = "none",
        lastGateAction: String = "ran",
        gateHits: String = "none=0",
        verdict: String = "stable",
        msSinceChange: Long = 5_000L,
        diff: Float = 2f,
        stash: String = "clean",
        ocrLines: Int = 5,
        ocrMs: Long = 120L,
        ocrAgeMs: Long = 1_000L,
        translateEngine: String = "ml_kit_en_ar",
        translateMs: Long = 300L,
        translateError: String? = null,
        translateAgeMs: Long = 1_000L,
        patchesPainted: Int = 4,
        patchesExpected: Int = 4,
        lastRemoval: String = "scroll",
        liveStatus: String = "Live: waiting · gate=none · src=stash · verdict=stable · stash=clean",
        heals: String = ""
    ) = LiveSelfCheck.Input(
        overlayPermission = overlayPermission,
        liveMode = liveMode,
        projectionActive = projectionActive,
        sessionExists = sessionExists,
        sessionAgeMs = sessionAgeMs,
        firstFrameMs = firstFrameMs,
        framesPerSecond = framesPerSecond,
        framesTotal = framesTotal,
        idleTicks = idleTicks,
        frameOrigin = frameOrigin,
        loopActive = loopActive,
        lastGate = lastGate,
        lastGateAction = lastGateAction,
        gateHits = gateHits,
        verdict = verdict,
        msSinceChange = msSinceChange,
        diff = diff,
        stash = stash,
        ocrLines = ocrLines,
        ocrMs = ocrMs,
        ocrAgeMs = ocrAgeMs,
        translateEngine = translateEngine,
        translateMs = translateMs,
        translateError = translateError,
        translateAgeMs = translateAgeMs,
        patchesPainted = patchesPainted,
        patchesExpected = patchesExpected,
        lastRemoval = lastRemoval,
        liveStatus = liveStatus,
        heals = heals
    )

    @Test
    fun selfCheck_allPass() {
        val stages = LiveSelfCheck.stages(selfCheckInput())
        assertEquals(12, stages.size)
        assertTrue(stages.all { it.result == LiveSelfCheck.Result.PASS })
        assertEquals(LiveSelfCheck.Result.PASS, LiveSelfCheck.overall(stages))
    }

    @Test
    fun selfCheck_stageOrderIsFixed() {
        val stages = LiveSelfCheck.stages(selfCheckInput())
        val expected = listOf(
            "overlay permission", "capture session", "first frame", "frames/s", "loop active",
            "last gate", "tracker verdict", "stash", "last OCR", "last translation",
            "patches painted", "last patch removal"
        )
        assertEquals(expected, stages.map { it.name })
    }

    @Test
    fun selfCheck_overlayPermissionMissing_fail() {
        val stages = LiveSelfCheck.stages(selfCheckInput(overlayPermission = false))
        assertEquals(LiveSelfCheck.Result.FAIL, stages[0].result)
        assertEquals(LiveSelfCheck.Result.FAIL, LiveSelfCheck.overall(stages))
    }

    @Test
    fun selfCheck_noSession_fail() {
        val stages = LiveSelfCheck.stages(
            selfCheckInput(liveMode = true, sessionExists = false, projectionActive = false)
        )
        assertEquals(LiveSelfCheck.Result.FAIL, stages[1].result) // capture session
    }

    @Test
    fun selfCheck_neverFirstFrame_fail() {
        val stages = LiveSelfCheck.stages(selfCheckInput(firstFrameMs = -1L, sessionAgeMs = 5_000L))
        assertEquals(LiveSelfCheck.Result.FAIL, stages[2].result) // first frame
    }

    @Test
    fun selfCheck_translationError_fail() {
        val stages = LiveSelfCheck.stages(selfCheckInput(translateError = "engine timeout"))
        assertEquals(LiveSelfCheck.Result.FAIL, stages[9].result) // last translation
        assertEquals(LiveSelfCheck.Result.FAIL, LiveSelfCheck.overall(stages))
    }

    @Test
    fun selfCheck_allWait_whenEverythingMissing() {
        // Live OFF, no session → capture session WAIT, first frame WAIT, etc.
        val stages = LiveSelfCheck.stages(
            selfCheckInput(
                liveMode = false, sessionExists = false, projectionActive = false,
                firstFrameMs = -1L, framesTotal = 0L, loopActive = false,
                lastGate = "none", verdict = "none", stash = "none",
                ocrAgeMs = -1L, translateAgeMs = -1L, patchesExpected = 0, lastRemoval = ""
            )
        )
        assertEquals(LiveSelfCheck.Result.WAIT, stages[1].result) // capture session → WAIT (Live OFF)
        assertEquals(LiveSelfCheck.Result.WAIT, LiveSelfCheck.overall(stages))
    }

    @Test
    fun selfCheck_gateNameOf() {
        assertEquals(LiveGates.GATE_NONE, LiveSelfCheck.gateNameOf("none"))
        assertEquals(LiveGates.GATE_NONE, LiveSelfCheck.gateNameOf(""))
        assertEquals("projection", LiveSelfCheck.gateNameOf("projection=none->pause"))
        assertEquals("screenOff", LiveSelfCheck.gateNameOf("screenOff=true->paused (screen off)"))
    }

    // -----------------------------------------------------------------------
    // 7. LiveSelfCheck.report format + key scrubbing
    // -----------------------------------------------------------------------

    @Test
    fun selfCheck_report_hasAllSections() {
        val input = selfCheckInput()
        val report = LiveSelfCheck.report(
            versionName = "0.9.5",
            versionCode = 19,
            input = input,
            extraLines = listOf("liveMode=true liveOptOut=false", "stash=clean"),
            logLines = listOf("I/MLCapture: frame arrived", "E/ManhwaLens: AIza1234567890 key leak"),
            nowMs = 1_700_000_000_000L
        )
        assertTrue(report.contains("ManhwaLens Live self-check — v0.9.5 (19)"))
        assertTrue(report.contains("OVERALL: PASS"))
        assertTrue(report.contains("— stages —"))
        assertTrue(report.contains("— state —"))
        assertTrue(report.contains("— last 2 log lines (MLCapture/ManhwaLens) —"))
        // exactly 12 stage lines (each starts with PASS / FAIL / WAIT padded)
        val stageLines = report.lines().count { it.startsWith("PASS ") || it.startsWith("FAIL ") || it.startsWith("WAIT ") }
        assertEquals(12, stageLines)
    }

    @Test
    fun selfCheck_report_scrubsApiKeys() {
        val input = selfCheckInput()
        val report = LiveSelfCheck.report(
            versionName = "0.9.5",
            versionCode = 19,
            input = input,
            extraLines = emptyList(),
            logLines = listOf("E/ManhwaLens: key=AIza1234567890abc", "W/ManhwaLens: bearer abcdef1234567890 token")
        )
        assertFalse(report.contains("AIza1234567890abc"))
        assertTrue(report.contains("[redacted-key]"))
        assertFalse(report.contains("bearer abcdef1234567890"))
        assertTrue(report.contains("bearer [redacted]"))
    }

    // -----------------------------------------------------------------------
    // 8. LiveLogRing — add, snapshot, capacity, scrub
    // -----------------------------------------------------------------------

    @Test
    fun liveLogRing_addAndSnapshot() {
        val ring = LiveLogRing(capacity = 3)
        ring.add("I", "MLCapture", "frame 1", atMs = 1)
        ring.add("I", "MLCapture", "frame 2", atMs = 2)
        ring.add("I", "MLCapture", "frame 3", atMs = 3)
        assertEquals(3, ring.size())
        val snap = ring.snapshot()
        assertEquals(3, snap.size)
        assertEquals("frame 3", snap[2].message)
        assertEquals("I/MLCapture: frame 3", snap[2].line())
    }

    @Test
    fun liveLogRing_capacityBound() {
        val ring = LiveLogRing(capacity = 2)
        ring.add("I", "T", "a", atMs = 1)
        ring.add("I", "T", "b", atMs = 2)
        ring.add("I", "T", "c", atMs = 3)
        assertEquals(2, ring.size())
        assertEquals(listOf("b", "c"), ring.snapshot().map { it.message })
    }

    @Test
    fun liveLogRing_scrubsKeys() {
        val ring = LiveLogRing(capacity = 10)
        ring.add("E", "ManhwaLens", "api_key=sk-secret123")
        ring.add("E", "ManhwaLens", "bearer token-abcdef1234567890")
        val text = ring.text()
        assertFalse(text.contains("sk-secret123"))
        assertTrue(text.contains("[redacted]"))
        assertFalse(text.contains("token-abcdef1234567890"))
        assertTrue(text.contains("bearer [redacted]"))
    }

    @Test
    fun liveLogRing_clear() {
        val ring = LiveLogRing(capacity = 10)
        ring.add("I", "T", "x", atMs = 1)
        ring.clear()
        assertEquals(0, ring.size())
        assertTrue(ring.text().isEmpty())
    }

    // -----------------------------------------------------------------------
    // 9. AppVisibility — counter, stuck detection, reconcile
    // -----------------------------------------------------------------------

    @Test
    fun appVisibility_startStopTransitions() {
        AppVisibility.shared.forceReset()
        assertTrue(AppVisibility.shared.onActivityStarted()) // 0→1
        assertTrue(AppVisibility.shared.isVisible())
        assertFalse(AppVisibility.shared.onActivityStarted()) // 1→2, not a transition
        assertFalse(AppVisibility.shared.onActivityStopped()) // 2→1, not a transition
        assertTrue(AppVisibility.shared.onActivityStopped()) // 1→0
        assertFalse(AppVisibility.shared.isVisible())
        assertFalse(AppVisibility.shared.onActivityStopped()) // already 0
    }

    @Test
    fun appVisibility_isStuckAndReconcile() {
        AppVisibility.shared.forceReset()
        AppVisibility.shared.onActivityStarted()
        assertTrue(AppVisibility.shared.isStuck(actualStarted = 0))
        assertEquals(0, AppVisibility.shared.reconcile(0))
        assertFalse(AppVisibility.shared.isStuck(actualStarted = 0))
    }

    // -----------------------------------------------------------------------
    // 10. End-to-end: gate chain → driver tick → self-check verdict
    // -----------------------------------------------------------------------

    @Test
    fun endToEnd_gateChainBlocksAndSelfCheckReflectsIt() {
        val gateLog = LiveGateLog()
        var lastDecision: LiveGates.Decision? = null

        fun gateDecision(input: LiveGates.Input): LiveGates.Decision {
            val d = LiveGates.evaluate(input)
            lastDecision = d
            if (d.gate != null) gateLog.record(d.gate)
            return d
        }

        // All gates clear → RUN
        val d1 = gateDecision(gatesInput())
        assertEquals(LiveGates.Outcome.RUN, d1.outcome)

    // App becomes visible (settings open) → gate pauses
    val d2 = gateDecision(gatesInput(appVisibleCounter = 1, appActuallyStarted = 1))
    assertEquals(LiveGates.Outcome.PAUSE, d2.outcome)
    assertEquals(LiveGates.GATE_APP_VISIBLE, d2.gate?.name)
    val gate2 = d2.gate ?: error("expected a blocking gate here")
    assertEquals(LiveGates.GATE_APP_VISIBLE, gate2.name)

    // Self-check stage 6 ("last gate") must reflect the blocking gate as FAIL
    val input = selfCheckInput(
        lastGate = gate2.wire(),
        lastGateAction = gate2.action,
        gateHits = gateLog.summary()
    )
    val stages = LiveSelfCheck.stages(input)
    assertEquals(LiveSelfCheck.Result.FAIL, stages[5].result) // last gate
    assertEquals(LiveSelfCheck.Result.FAIL, LiveSelfCheck.overall(stages))
    assertNotNull(gate2.line())
    }
}
