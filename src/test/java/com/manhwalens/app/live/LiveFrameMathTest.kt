package com.manhwalens.app.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LiveFrameMathTest {

    private fun randomGray(seed: Long, size: Int): ByteArray {
        val rnd = Random(seed)
        return ByteArray(size) { rnd.nextBytes(1)[0] }
    }

    private fun shiftRows(gray: ByteArray, w: Int, h: Int, shift: Int): ByteArray {
        // Build a new frame where content moved by `shift` rows.
        // New rows outside the shared band are fresh random (simulating newly revealed content).
        val rnd = Random(999)
        val out = ByteArray(gray.size)
        val from = maxOf(0, -shift)
        val to = minOf(h, h - shift)
        var i = 0
        for (y in 0 until h) {
            val dst = y * w
            if (y in from until to) {
                val src = (y + shift) * w
                System.arraycopy(gray, src, out, dst, w)
            } else {
                for (x in 0 until w) out[dst + x] = rnd.nextBytes(1)[0]
            }
            i++
        }
        return out
    }

    @Test
    fun identicalFrames_zeroDiff() {
        val g = randomGray(1, 64 * 128)
        assertEquals(0f, LiveFrameMath.meanAbsoluteDifference(g, g.copyOf()), 0.001f)
    }

    @Test
    fun differentFrames_largeDiff() {
        val a = randomGray(1, 64 * 128)
        val b = randomGray(2, 64 * 128)
        assertTrue(LiveFrameMath.meanAbsoluteDifference(a, b) > 20f)
    }

    @Test
    fun nearlyEqual_withinTolerance() {
        val g = randomGray(3, 64 * 128)
        val noisy = g.copyOf()
        // flip a tiny number of pixels by 1 step
        noisy[0] = (noisy[0] + 1).toByte()
        assertTrue(LiveFrameMath.nearlyEqual(g, noisy, tolerance = 2f))
    }

    @Test
    fun stability_reportsChangingThenStable() {
        val tracker = LiveStabilityTracker(changeThreshold = 10f, stableWindowMs = 400)
        var t = 0L
        val frame1 = randomGray(11, 64 * 128)
        val frame2 = randomGray(12, 64 * 128)

        assertEquals(LiveStabilityTracker.Verdict.Changing, tracker.onFrame(frame1, t))
        t += 333
        assertEquals(LiveStabilityTracker.Verdict.Changing, tracker.onFrame(frame2, t)) // different content
        t += 100
        assertEquals(LiveStabilityTracker.Verdict.Settling, tracker.onFrame(frame2.copyOf(), t))
        t += 400
        assertEquals(LiveStabilityTracker.Verdict.Stable, tracker.onFrame(frame2.copyOf(), t))
    }

    @Test
    fun stability_resetForgetsHistory() {
        val tracker = LiveStabilityTracker(10f, 400)
        val frame = randomGray(21, 64 * 128)
        tracker.onFrame(frame, 0)
        tracker.reset()
        // After reset the very first frame is treated as a fresh (changing) frame.
        assertEquals(LiveStabilityTracker.Verdict.Changing, tracker.onFrame(frame, 10))
    }

    @Test
    fun rowShift_scrollDown_estimatesPositiveShift() {
        val w = 64; val h = 128
        val content = randomGray(31, w * h)
        val shifted = shiftRows(content, w, h, shift = 40) // user scrolled down
        val est = RowHashShift.estimate(content, shifted, w, h)
        assertNotNull("expected a shift estimate", est)
        assertEquals(40, est!!.rows)
        assertTrue("confidence ${est.confidence}", est.confidence >= 0.6f)
    }

    @Test
    fun rowShift_scrollUp_estimatesNegativeShift() {
        val w = 64; val h = 128
        val content = randomGray(32, w * h)
        val shifted = shiftRows(content, w, h, shift = -25) // user scrolled back up
        val est = RowHashShift.estimate(content, shifted, w, h)
        assertNotNull(est)
        assertEquals(-25, est!!.rows)
    }

    @Test
    fun rowShift_unrelatedFrames_returnsNull() {
        val w = 64; val h = 128
        val a = randomGray(41, w * h)
        val b = randomGray(42, w * h)
        assertNull(RowHashShift.estimate(a, b, w, h, minConfidence = 0.7f))
    }
}
