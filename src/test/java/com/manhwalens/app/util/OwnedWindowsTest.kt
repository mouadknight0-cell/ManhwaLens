package com.manhwalens.app.util

import com.manhwalens.app.ocr.geometry.Rect2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnedWindowsTest {

    private fun rect(l: Float, t: Float, r: Float, b: Float) = Rect2D(l, t, r, b)

    /** A clock the test can advance. */
    private class VarClock(var now: Long) {
        fun ms(): Long = now
    }

    @Test
    fun addAndCurrentRects() {
        val c = VarClock(10_000L)
        val w = OwnedWindows { c.ms() }
        w.add("lens", rect(10f, 10f, 60f, 60f))
        w.add("menu", rect(100f, 100f, 200f, 200f))
        assertEquals(listOf(rect(10f, 10f, 60f, 60f), rect(100f, 100f, 200f, 200f)),
            w.currentRects())
    }

    @Test
    fun removeStartsGraceWindow() {
        val c = VarClock(10_000L)
        val w = OwnedWindows { c.ms() }
        w.add("caption", rect(0f, 0f, 50f, 50f))
        w.remove("caption")
        // Within grace (1 s): still counts as ours.
        assertTrue(w.allRects().any { it == rect(0f, 0f, 50f, 50f) })
        assertTrue(w.recentlyRemovedRects().any { it == rect(0f, 0f, 50f, 50f) })
    }

    @Test
    fun removedRectExpiresAfterGrace() {
        val c = VarClock(10_000L)
        val w = OwnedWindows { c.ms() }
        w.add("caption", rect(0f, 0f, 50f, 50f))
        w.remove("caption")
        c.now = 11_100L // 1.1 s later → outside grace
        assertFalse(w.allRects().any { it == rect(0f, 0f, 50f, 50f) })
        assertTrue(w.recentlyRemovedRects().isEmpty())
    }

    @Test
    fun removeAtExactlyGraceBoundaryIsStillIncluded() {
        val c = VarClock(10_000L)
        val w = OwnedWindows { c.ms() }
        w.add("caption", rect(0f, 0f, 50f, 50f))
        w.remove("caption")
        c.now = 11_000L // exactly GRACE_MS later
        assertTrue(w.allRects().any { it == rect(0f, 0f, 50f, 50f) })
    }

    @Test
    fun isOwnString_catchesUiWords() {
        val w = OwnedWindows { 0L }
        assertTrue(w.isOwnString("Watching"))
        assertTrue(w.isOwnString(" SCANNING "))
        assertTrue(w.isOwnString("مشاهدة"))
        assertTrue(w.isOwnString("Tap to start"))
        assertFalse(w.isOwnString("Hello world"))
        assertFalse(w.isOwnString("a real bubble line"))
    }

    @Test
    fun uiStrings_areLowercaseTrimmed() {
        assertTrue(OwnedWindows.UI_STRINGS.all { it == it.trim().lowercase() })
    }
}
