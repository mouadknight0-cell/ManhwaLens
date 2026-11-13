package com.manhwalens.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Part A scenarios with fake frames (64x128 bytes, THUMB luma):
 *  - scrolling (large change) → Changing, then settles → Stable after window
 *  - static page → stable verdicts
 *  - blinking cursor (tiny change) → never a "change" verdict
 */
class LiveScenarioTest {

    private fun frame(seed: Int, size: Int = LiveFrameMath.THUMB_W * LiveFrameMath.THUMB_H): ByteArray =
        ByteArray(size) { i -> ((i * 31 + seed * 17 + (i / 64) * 7) % 256).toByte() }

    private fun frameWithCursor(base: ByteArray, cursorOn: Boolean): ByteArray {
        val c = base.copyOf()
        for (i in 100 until 112) c[i] = (if (cursorOn) 255 else 0).toByte()
        return c
    }

    @Test
    fun scrollingPageThenStop_reportsChangingThenStable() {
        val tracker = LiveStabilityTracker(changeThreshold = 10f, stableWindowMs = 400L)
        var now = 1_000L
        val f1 = frame(1)
        assertEquals(LiveStabilityTracker.Verdict.Changing, tracker.onFrame(f1, now))   // first frame
        now += 333
        val f2 = frame(99)  // scrolled 20+ rows — totally different content
        assertEquals(LiveStabilityTracker.Verdict.Changing, tracker.onFrame(f2, now))
        now += 200  // not yet 400 ms since the change
        assertEquals(LiveStabilityTracker.Verdict.Settling, tracker.onFrame(f2.copyOf(), now))
        now += 250  // 450 ms since the last change
        assertEquals(LiveStabilityTracker.Verdict.Stable, tracker.onFrame(f2.copyOf(), now))
    }

    @Test
    fun staticPage_isStableAfterWindow() {
        val tracker = LiveStabilityTracker(changeThreshold = 10f, stableWindowMs = 400L)
        val f = frame(7)
        var now = 5_000L
        tracker.onFrame(f, now)
        now += 500
        assertEquals(LiveStabilityTracker.Verdict.Stable, tracker.onFrame(f.copyOf(), now))
    }

    @Test
    fun blinkingCursor_doesNotCountAsChange() {
        val tracker = LiveStabilityTracker(changeThreshold = 10f, stableWindowMs = 400L)
        val base = frame(42)
        var now = 1_000L
        tracker.onFrame(base, now)
        // 12 changed bytes out of 8192 ≈ mean diff 0.36 — far below 10f
        for (i in 1..6) {
            now += 333
            val verdict = tracker.onFrame(frameWithCursor(base, i % 2 == 0), now)
            assertTrue("cursor blink must not be a change (got $verdict)", verdict != LiveStabilityTracker.Verdict.Changing)
        }
    }

    /**
     * The v0.9.3 root cause at tracker level: MediaProjection delivers NOTHING once the
     * screen is still. Feeding the SAME stashed thumbnail (diff 0) must still walk
     * Changing → Settling → Stable, because stability is a function of TIME.
     */
    @Test
    fun noNewFrames_reusingTheStashedThumbnailStillReachesStable() {
        val tracker = LiveStabilityTracker(changeThreshold = 10f, stableWindowMs = 400L)
        var now = 1_000L
        tracker.onFrame(frame(1), now)          // scroll frame #1
        now += 333
        val lastScrollFrame = frame(99)
        assertEquals(LiveStabilityTracker.Verdict.Changing, tracker.onFrame(lastScrollFrame, now))
        // The screen goes still: no new frames, the same thumbnail is reused every tick.
        now += 333
        assertEquals(LiveStabilityTracker.Verdict.Settling, tracker.onFrame(lastScrollFrame, now))
        now += 333
        assertEquals(LiveStabilityTracker.Verdict.Stable, tracker.onFrame(lastScrollFrame, now))
        now += 333
        assertEquals("stays stable while nothing changes", LiveStabilityTracker.Verdict.Stable, tracker.onFrame(lastScrollFrame, now))
    }

    @Test
    fun tinyPauseInScroll_isSettlingNotStable() {
        val tracker = LiveStabilityTracker(changeThreshold = 10f, stableWindowMs = 400L)
        var now = 1_000L
        tracker.onFrame(frame(1), now)
        now += 100
        tracker.onFrame(frame(2), now) // change
        now += 100                      // only 200 ms since change
        assertEquals(LiveStabilityTracker.Verdict.Settling, tracker.onFrame(frame(2), now))
    }
}
