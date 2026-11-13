package com.manhwalens.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Part A / Part E — the frame-starvation regression suite.
 *
 * Every test runs the PURE [LiveLoopDriver] against a fake [LiveLoopDriver.FrameSource]
 * and a fake clock, so "the screen went still" (no new frames at all) is simulated
 * exactly the way MediaProjection behaves on a real device. v0.9.3's LiveScenarioTest
 * fed the tracker directly and therefore never covered that case.
 */
class LiveLoopDriverTest {

    // ------------------------------------------------------------------ fakes

    private class FakeFrame(val id: Int, val gray: ByteArray)

    private class FakeClock(var now: Long = 10_000L) {
        fun advance(ms: Long) { now += ms }
    }

    private class FakeSource : LiveLoopDriver.FrameSource<FakeFrame> {
        /** Frames the mirror still has to deliver (empty = the screen went still). */
        val queue = ArrayDeque<FakeFrame>()
        var visiblePatches = 0
        var fadingPatches = 0
        var overlayDrawn = true
        var mask: BooleanArray? = null
        var shiftReturnsTrue = true

        val released = mutableListOf<Int>()
        var shiftCalls = 0
        var hideCalls = 0
        var showCalls = 0
        var awaitCalls = 0
        var awaitReturns: FakeFrame? = null
        var awaitTimesOut = false
        var thumbnailFails = false

        override fun acquireNewFrame(): FakeFrame? = if (queue.isEmpty()) null else queue.removeFirst()

        override fun grayOf(frame: FakeFrame): ByteArray {
            if (thumbnailFails) throw IllegalStateException("thumbnail boom")
            return frame.gray
        }

        override fun diffMask(): BooleanArray? = mask

        override fun hasPatchesOnScreen(): Boolean = visiblePatches + fadingPatches > 0

        override fun patchesBurnedInNow(): Boolean = hasPatchesOnScreen() && overlayDrawn

        override fun patchCounts() = LiveLoopDriver.PatchCounts(visiblePatches, fadingPatches)

        override fun releaseFrame(frame: FakeFrame) {
            released += frame.id
        }

        override fun onShiftPatches(frame: FakeFrame, dyThumbnailRows: Int, confidence: Float): Boolean {
            shiftCalls++
            return shiftReturnsTrue && hasPatchesOnScreen()
        }

        override fun hideOverlay(): Boolean {
            hideCalls++
            overlayDrawn = false
            return true
        }

        override fun awaitNewFrame(timeoutMs: Long): FakeFrame? {
            awaitCalls++
            assertEquals("clean-capture wait must use the 400 ms window", 400L, timeoutMs)
            return if (awaitTimesOut) null else awaitReturns
        }

        override fun showOverlay() {
            showCalls++
            overlayDrawn = true
        }
    }

    private class RecordingSink : LiveLoopDriver.Sink<FakeFrame> {
        val statuses = mutableListOf<LiveLoopDriver.DriverStatus>()
        data class Translation(val frame: FakeFrame, val gray: ByteArray, val origin: String)
        val translations = mutableListOf<Translation>()
        val clears = mutableListOf<String>()
        var patchesMovedOrCleared = 0
        val noFramesYet = mutableListOf<Long>()
        val starved = mutableListOf<Long>()
        val cleanTimeouts = mutableListOf<Int>()
        val traces = mutableListOf<String>()
        val errors = mutableListOf<String>()
        var diagnostics: LiveLoopDriver.Diagnostics? = null

        override fun onStatus(status: LiveLoopDriver.DriverStatus) { statuses += status }
        override fun onTranslate(frame: FakeFrame, gray: ByteArray, frameOrigin: String) {
            translations += Translation(frame, gray, frameOrigin)
        }
        override fun onClearPatches(reason: String) { clears += reason }
        override fun onPatchesMovedOrCleared() { patchesMovedOrCleared++ }
        override fun onNoFramesYet(elapsedMs: Long) { noFramesYet += elapsedMs }
        override fun onStarved(msSinceLastFrame: Long, verdict: LiveStabilityTracker.Verdict) {
            starved += msSinceLastFrame
        }
        override fun onCleanCaptureTimeout(consecutiveAttempts: Int) { cleanTimeouts += consecutiveAttempts }
        override fun onDiagnostics(diagnostics: LiveLoopDriver.Diagnostics) { this.diagnostics = diagnostics }
        override fun trace(stage: String, detail: String) { traces += "$stage: $detail" }
        override fun onError(what: String) { errors += what }
    }

    private class Harness(
        stableWindowMs: Long = 400L,
        changeThreshold: Float = 10f
    ) {
        val clock = FakeClock()
        val source = FakeSource()
        val sink = RecordingSink()
        val driver = LiveLoopDriver(source, sink, { clock.now }, stableWindowMs, changeThreshold)

        init {
            driver.startSession(clock.now)
        }

        /** Feed one frame as "the mirror delivered it", then tick. */
        fun deliver(frame: FakeFrame, advanceMs: Long = TICK): Long {
            source.queue += frame
            clock.advance(advanceMs)
            return driver.tick()
        }

        /** Tick with NO new frame available (the screen went still). */
        fun still(advanceMs: Long = TICK): Long {
            clock.advance(advanceMs)
            return driver.tick()
        }

        companion object {
            const val TICK = 333L
        }
    }

    private fun frame(id: Int, seed: Int): FakeFrame =
        FakeFrame(id, ByteArray(LiveFrameMath.THUMB_W * LiveFrameMath.THUMB_H) { i ->
            ((i * 31 + seed * 17 + (i / 64) * 7) % 256).toByte()
        })

    /** A synthetic page whose rows move vertically — RowHashShift detects the shift. */
    private fun page(id: Int, offsetRows: Int): FakeFrame {
        val w = LiveFrameMath.THUMB_W
        val h = LiveFrameMath.THUMB_H
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val base = ((y + offsetRows) * 7) % 100
            for (x in 0 until w) out[y * w + x] = (base + x / 8).toByte()
        }
        return FakeFrame(id, out)
    }

    /** Pseudo-random noise: never matches a vertical shift (RowHashShift returns null). */
    private fun noise(id: Int, seed: Int): FakeFrame {
        val out = ByteArray(LiveFrameMath.THUMB_W * LiveFrameMath.THUMB_H)
        var x = seed * 7919 + 13
        for (i in out.indices) {
            x = (x * 1103515245 + 12345) and 0x7FFFFFFF
            out[i] = ((x shr 9) and 0xFF).toByte()
        }
        return FakeFrame(id, out)
    }

    private fun withCursor(base: FakeFrame, id: Int, cursorOn: Boolean): FakeFrame {
        val c = base.gray.copyOf()
        for (i in 100 until 112) c[i] = (if (cursorOn) 255 else 0).toByte()
        return FakeFrame(id, c)
    }

    // ==================================================================
    // (1) scroll, then NO new frames → Stable + translation request
    // ==================================================================

    @Test
    fun scrollThenScreenGoesStill_reachesStableAndTranslatesWithinWindowPlusOneTick() {
        val h = Harness()
        h.deliver(frame(1, 1))
        h.deliver(frame(2, 99))
        h.deliver(frame(3, 55))
        assertEquals("still scrolling — nothing may be translated yet", 0, h.sink.translations.size)
        assertTrue(h.sink.statuses.contains(LiveLoopDriver.DriverStatus.SCANNING))

        // The screen is now STILL: MediaProjection delivers nothing more.
        val budget = 400L + Harness.TICK      // stableWindow + 1 tick
        var elapsed = 0L
        while (h.sink.translations.isEmpty() && elapsed < budget) {
            h.still()
            elapsed += Harness.TICK
        }

        assertEquals("the stashed frame must be translated once the page is still", 1, h.sink.translations.size)
        assertTrue("translation must arrive within stableWindow + 1 tick (took ${elapsed}ms)", elapsed <= budget)
        assertEquals("the LAST frame before the screen went still is the one translated", 3, h.sink.translations[0].frame.id)
        assertEquals("stash", h.sink.translations[0].origin)
        assertTrue(h.sink.statuses.contains(LiveLoopDriver.DriverStatus.TRANSLATING))
        // No new frame arrived, yet the tracker advanced → the starvation bug is gone.
        assertEquals(0, h.source.queue.size)
        assertEquals(LiveLoopDriver.FrameOrigin.STASH, h.sink.diagnostics?.frameOrigin)
        assertTrue("idle ticks must be counted", (h.sink.diagnostics?.ticksWithoutNewFrame ?: 0L) >= 2L)
    }

    @Test
    fun frameWithPatchesOnScreenIsStashedNotThrownAway() {
        val h = Harness()
        // Patches are up while the page scrolls (the normal in-place translation state).
        h.source.visiblePatches = 3
        h.deliver(page(1, 0))
        h.deliver(page(2, 6))
        // v0.9.3 recycled BOTH frames here because hasPatches() was true → nothing was
        // stashed → acquireLiveBitmap() returned null forever → the tracker starved.
        assertEquals(
            "only the SUPERSEDED stash frame is recycled, never one merely carrying patches",
            listOf(1), h.source.released
        )
        assertTrue("the newest frame must be stashed even with patches on screen", h.driver.hasStash())
        assertEquals(LiveLoopDriver.StashState.BURNED, h.driver.stashState())
        assertNotNull(h.driver.takeStashedFrameIfClean() == null)
    }

    // ==================================================================
    // (2) same, with patches visible / fading / shifted
    // ==================================================================

    @Test
    fun stillScreenWithVisiblePatches_hidesOverlayAndWaitsForACleanFrame() {
        val h = Harness()
        h.source.visiblePatches = 2
        h.deliver(frame(1, 1))
        h.deliver(frame(2, 77))
        h.source.awaitReturns = frame(900, 77)

        var elapsed = 0L
        while (h.sink.translations.isEmpty() && elapsed < 5 * Harness.TICK) {
            h.still(); elapsed += Harness.TICK
        }

        assertEquals(1, h.sink.translations.size)
        assertEquals("a burned-in stash must be replaced by a freshly awaited frame", 900, h.sink.translations[0].frame.id)
        assertEquals("fresh", h.sink.translations[0].origin)
        assertEquals("overlay must be hidden for the clean capture", 1, h.source.hideCalls)
        assertEquals("overlay must be restored afterwards", 1, h.source.showCalls)
        assertEquals(1, h.source.awaitCalls)
        assertTrue(h.source.overlayDrawn)
    }

    @Test
    fun stillScreenWithFadingPatches_treatsThemAsBurnedIn() {
        val h = Harness()
        h.source.visiblePatches = 2
        h.deliver(frame(1, 1))
        h.deliver(frame(2, 33))
        // clearPatches() keeps the slots for the 80 ms fade-out tail — v0.9.3 read that as "clean".
        h.source.visiblePatches = 0
        h.source.fadingPatches = 2
        h.source.awaitReturns = frame(901, 33)

        var elapsed = 0L
        while (h.sink.translations.isEmpty() && elapsed < 5 * Harness.TICK) {
            h.still(); elapsed += Harness.TICK
        }

        assertEquals(1, h.sink.translations.size)
        assertEquals(901, h.sink.translations[0].frame.id)
        assertEquals(1, h.source.hideCalls)
        assertEquals("fading patches must be reported for the debug panel", 2, h.sink.diagnostics?.patches?.fading)
    }

    @Test
    fun scrollingWithPatches_shiftsThemInsteadOfClearing() {
        val h = Harness()
        h.deliver(page(1, 0))               // first frame: nothing to compare yet (v0.9.3 cleared here too)
        val clearsBeforeScroll = h.sink.clears.size
        h.source.visiblePatches = 4
        h.deliver(page(2, 5))
        h.deliver(page(3, 10))

        assertTrue("patches must ride the scroll", h.source.shiftCalls >= 1)
        assertEquals("shifted patches must NOT be cleared", clearsBeforeScroll, h.sink.clears.size)
        assertTrue(
            "a shift must invalidate the same-page memory",
            h.sink.patchesMovedOrCleared >= 1
        )
        assertTrue(h.sink.traces.any { it.startsWith("live patch shift") })
    }

    @Test
    fun scrollingWithPatchesWhenShiftFails_clearsWithAReason() {
        val h = Harness()
        h.source.visiblePatches = 4
        h.source.shiftReturnsTrue = false      // nothing moved (e.g. all patches off-screen)
        h.deliver(page(1, 0))
        val clearsBeforeScroll = h.sink.clears.size
        h.deliver(noise(2, 88))               // not a vertical shift → page change

        assertEquals("the clear must carry a removal reason", clearsBeforeScroll + 1, h.sink.clears.size)
        assertEquals("page change", h.sink.clears.last())
        assertTrue(h.sink.patchesMovedOrCleared >= 1)
    }

    @Test
    fun shiftedThenStill_translatesTheNewPageAndNotTheOldOne() {
        val h = Harness()
        h.source.visiblePatches = 3
        h.deliver(page(1, 0))
        h.deliver(page(2, 8))
        // Screen goes still right after the scroll (patches were shifted, not cleared).
        h.source.awaitReturns = page(902, 8)
        var elapsed = 0L
        while (h.sink.translations.isEmpty() && elapsed < 6 * Harness.TICK) {
            h.still(); elapsed += Harness.TICK
        }
        assertEquals(1, h.sink.translations.size)
        assertEquals(902, h.sink.translations[0].frame.id)
    }

    // ==================================================================
    // (3) a burned-in stash triggers a clean capture and is NEVER OCR'd
    // ==================================================================

    @Test
    fun burnedInStashIsNeverHandedToTranslation() {
        val h = Harness()
        h.source.visiblePatches = 2
        h.deliver(frame(1, 1))
        h.deliver(frame(7, 42))            // captured with our Arabic on screen → burned
        h.source.awaitReturns = frame(800, 42)

        var elapsed = 0L
        while (h.sink.translations.isEmpty() && elapsed < 5 * Harness.TICK) {
            h.still(); elapsed += Harness.TICK
        }

        assertEquals(1, h.sink.translations.size)
        assertFalse(
            "the burned-in stash frame must never reach OCR",
            h.sink.translations.any { it.frame.id == 7 }
        )
        assertEquals(
            "the awaited frame was captured with the overlay hidden → the stash is clean again",
            LiveLoopDriver.StashState.CLEAN, h.driver.stashState()
        )
        assertTrue(h.sink.traces.any { "frame=fresh" in it })
    }

    @Test
    fun cleanStashIsUsedDirectlyWithoutHidingTheOverlay() {
        val h = Harness()
        h.deliver(frame(1, 1))
        h.deliver(frame(2, 5))             // no patches at all → clean stash
        var elapsed = 0L
        while (h.sink.translations.isEmpty() && elapsed < 5 * Harness.TICK) {
            h.still(); elapsed += Harness.TICK
        }
        assertEquals(1, h.sink.translations.size)
        assertEquals(2, h.sink.translations[0].frame.id)
        assertEquals("stash", h.sink.translations[0].origin)
        assertEquals("a clean stash needs no hide/wait round-trip", 0, h.source.hideCalls)
        assertEquals(0, h.source.awaitCalls)
    }

    // ==================================================================
    // (4) clean-capture timeout → abort + retry, never OCR the burned frame
    // ==================================================================

    @Test
    fun cleanCaptureTimeout_abortsTheCycleAndRetriesOnTheNextTick() {
        val h = Harness()
        h.source.visiblePatches = 2
        h.deliver(frame(1, 1))
        h.deliver(frame(7, 42))
        h.source.awaitTimesOut = true

        // Settle to Stable with a timing-out clean capture.
        repeat(6) { h.still() }
        assertEquals("nothing may be translated while the clean capture times out", 0, h.sink.translations.size)
        assertTrue("the abort must be reported", h.sink.cleanTimeouts.isNotEmpty())
        assertEquals("overlay must be restored after a timeout", true, h.source.overlayDrawn)
        assertTrue(h.sink.traces.any { "timed out" in it })
        assertFalse(h.sink.statuses.contains(LiveLoopDriver.DriverStatus.TRANSLATING))

        // The mirror recovers → the very next tick retries and succeeds.
        h.source.awaitTimesOut = false
        h.source.awaitReturns = frame(801, 42)
        h.still()
        assertEquals(1, h.sink.translations.size)
        assertEquals(801, h.sink.translations[0].frame.id)
        assertFalse(h.sink.translations.any { it.frame.id == 7 })
    }

    @Test
    fun repeatedCleanCaptureTimeouts_backOffButKeepRetrying() {
        val h = Harness()
        h.source.visiblePatches = 1
        h.deliver(frame(1, 3))
        h.source.awaitTimesOut = true
        var delay = 0L
        repeat(8) { delay = h.still() }
        assertEquals("after repeated timeouts the tick backs off", LiveLoopDriver.BACKOFF_TICK_MS, delay)
        assertTrue(h.sink.cleanTimeouts.size >= 5)
        assertEquals(0, h.sink.translations.size)
    }

    // ==================================================================
    // (7) a static page with a blinking cursor must not retrigger
    // ==================================================================

    @Test
    fun staticPageWithBlinkingCursor_translatesOnceAndNeverRetriggers() {
        val h = Harness()
        val base = frame(1, 42)
        h.deliver(base)
        h.source.awaitReturns = null

        // Settle → one translation.
        var elapsed = 0L
        while (h.sink.translations.isEmpty() && elapsed < 5 * Harness.TICK) {
            h.still(); elapsed += Harness.TICK
        }
        assertEquals(1, h.sink.translations.size)
        h.driver.noteTranslated(h.sink.translations[0].gray)
        val clearsBeforeBlink = h.sink.clears.size

        // 15 s of cursor blinking (12 changed bytes out of 8192) — no retrigger.
        var blink = 0
        repeat(45) {
            blink++
            h.deliver(withCursor(base, 100 + blink, blink % 2 == 0))
        }
        assertEquals("a blinking cursor must not retrigger translation", 1, h.sink.translations.size)
        assertEquals("a blinking cursor must not clear the patches", clearsBeforeBlink, h.sink.clears.size)
        assertEquals("…and must not shift them either", 0, h.source.shiftCalls)
        assertTrue(
            "same-page verdicts must be reported",
            h.sink.statuses.contains(LiveLoopDriver.DriverStatus.SKIPPED)
        )
        assertTrue((h.sink.diagnostics?.samePageSkips ?: 0L) > 0L)
    }

    // ==================================================================
    // watchdogs, starvation logging, diagnostics
    // ==================================================================

    @Test
    fun noFrameEverWithinThreeSeconds_requestsVirtualDisplayRecreateOnce() {
        val h = Harness()
        // 4 s of ticks with an empty mirror.
        repeat(12) { h.still() }
        assertEquals("the recreate must be requested exactly once", 1, h.sink.noFramesYet.size)
        assertTrue(h.sink.noFramesYet[0] >= 3_000L)
        assertEquals(LiveLoopDriver.FrameOrigin.NONE, h.sink.diagnostics?.frameOrigin)
        assertEquals(LiveLoopDriver.StashState.NONE, h.sink.diagnostics?.stash)
    }

    @Test
    fun starvationIsLoggedAtMostOncePerSecond() {
        val h = Harness()
        h.deliver(frame(1, 1))
        h.deliver(frame(2, 90))
        // Force a persistent Settling verdict with no new frames by keeping the window long.
        h.driver.stableWindowMs = 5_000L
        repeat(9) { h.still(Harness.TICK) }     // 3 s of stillness
        assertTrue("starvation must be reported", h.sink.starved.isNotEmpty())
        assertTrue("…but at most once per second", h.sink.starved.size <= 4)
    }

    @Test
    fun diagnosticsReportFrameSourceVerdictAndStashState() {
        val h = Harness()
        h.source.visiblePatches = 1
        h.deliver(frame(1, 1))
        assertEquals(LiveLoopDriver.FrameOrigin.NEW, h.sink.diagnostics?.frameOrigin)
        assertEquals(LiveLoopDriver.StashState.BURNED, h.sink.diagnostics?.stash)
        h.still()
        assertEquals(LiveLoopDriver.FrameOrigin.STASH, h.sink.diagnostics?.frameOrigin)
        val lines = h.sink.diagnostics?.lines() ?: emptyList()
        assertTrue(lines.any { "frames/s=" in it && "source=stash" in it })
        assertTrue(lines.any { "verdict=" in it && "diff=" in it })
        assertTrue(lines.any { "stash=burned" in it })
    }

    @Test
    fun thumbnailFailureIsReportedAndDoesNotKillTheLoop() {
        val h = Harness()
        h.source.thumbnailFails = true
        h.source.queue += frame(1, 1)
        val delay = h.driver.tick()
        assertEquals(Harness.TICK, delay)
        assertTrue(h.sink.errors.any { "thumbnail" in it })
        assertEquals("the broken frame must be released", listOf(1), h.source.released)
    }

    @Test
    fun stashFrameIsRecycledWhenTheNextFrameArrives() {
        val h = Harness()
        h.deliver(frame(1, 1))
        h.deliver(frame(2, 2))
        h.deliver(frame(3, 3))
        assertEquals("only superseded stash frames are recycled", listOf(1, 2), h.source.released)
    }

    @Test
    fun invalidateSamePageForcesAFreshAttemptOnTheNextStablePass() {
        val h = Harness()
        h.deliver(frame(1, 5))
        var elapsed = 0L
        while (h.sink.translations.isEmpty() && elapsed < 5 * Harness.TICK) {
            h.still(); elapsed += Harness.TICK
        }
        assertEquals(1, h.sink.translations.size)

        // Same page again → skipped as "same page" (the attempted thumbnail matches).
        repeat(3) { h.still() }
        assertEquals(1, h.sink.translations.size)
        assertTrue(h.sink.statuses.contains(LiveLoopDriver.DriverStatus.SKIPPED))

        // Patches were shifted with a scroll or cleared → the next Stable pass must run.
        // The first pass consumed the stashed frame, so the mirror must be able to hand
        // over a clean one (in the app: hiding the overlay triggers that redraw).
        h.source.visiblePatches = 2
        h.source.awaitReturns = frame(50, 5)
        h.driver.invalidateSamePage()
        h.still()
        assertEquals("the same page must be attempted again", 2, h.sink.translations.size)
        assertEquals(50, h.sink.translations[1].frame.id)
    }

    @Test
    fun pageAlreadyTranslatedIsNotReTranslatedEvenAfterInvalidation() {
        val h = Harness()
        h.deliver(frame(1, 5))
        var elapsed = 0L
        while (h.sink.translations.isEmpty() && elapsed < 5 * Harness.TICK) {
            h.still(); elapsed += Harness.TICK
        }
        h.driver.noteTranslated(h.sink.translations[0].gray)
        // The user cleared the translation on purpose: the translated-page memory keeps
        // the loop from painting it again immediately.
        h.driver.invalidateSamePage()
        repeat(4) { h.still() }
        assertEquals(1, h.sink.translations.size)
    }

    @Test
    fun adoptFrameKeepsAProbedFrameUsableOnAStillScreen() {
        val h = Harness()
        val probed = frame(5, 12)
        h.driver.resetTracker()
        h.driver.adoptFrame(probed, probed.gray)
        assertTrue(h.driver.hasStash())
        var elapsed = 0L
        while (h.sink.translations.isEmpty() && elapsed < 5 * Harness.TICK) {
            h.still(); elapsed += Harness.TICK
        }
        assertEquals("an adopted frame must be translatable without any new mirror frame", 1, h.sink.translations.size)
        assertEquals(5, h.sink.translations[0].frame.id)
    }

    @Test
    fun takeStashedFrameIfClean_refusesABurnedFrame() {
        val h = Harness()
        h.source.visiblePatches = 2
        h.deliver(frame(1, 1))
        assertNull("a burned-in stash must never be handed to the tap path", h.driver.takeStashedFrameIfClean())

        val h2 = Harness()
        h2.deliver(frame(2, 2))
        assertNotNull(h2.driver.takeStashedFrameIfClean())
        assertNull("…and only once", h2.driver.takeStashedFrameIfClean())
    }

    @Test
    fun tickDelayStaysAtThreeFps() {
        val h = Harness()
        h.deliver(frame(1, 1))
        assertEquals(LiveLoopDriver.TICK_MS, h.still())
        assertEquals(333L, LiveLoopDriver.TICK_MS)
        assertEquals(400L, LiveLoopDriver.CLEAN_CAPTURE_TIMEOUT_MS)
        assertEquals(3_000L, LiveLoopDriver.NO_FRAMES_RECREATE_AFTER_MS)
        assertEquals(400L, LiveLoopDriver.DEFAULT_STABLE_WINDOW_MS)
    }
}
