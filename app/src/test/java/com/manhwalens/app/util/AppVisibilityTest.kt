package com.manhwalens.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVisibilityTest {

    @Test
    fun startsHidden() {
        assertFalse(AppVisibility().isVisible())
    }

    @Test
    fun firstStartMakesVisibleAndLastStopHides() {
        val v = AppVisibility()
        assertTrue(v.onActivityStarted())   // 0->1 transition
        assertTrue(v.isVisible())
        assertTrue(v.onActivityStopped())   // 1->0 transition
        assertFalse(v.isVisible())
    }

    @Test
    fun nestedActivitiesDoNotFlapVisibility() {
        val v = AppVisibility()
        assertTrue(v.onActivityStarted())     // MainActivity
        assertFalse(v.onActivityStarted())    // SettingsActivity on top
        assertFalse(v.onActivityStopped())    // Settings dismissed, MainActivity still visible
        assertTrue(v.isVisible())
        assertTrue(v.onActivityStopped())     // app hidden
        assertFalse(v.isVisible())
    }

    @Test
    fun overStoppedNeverGoesNegative() {
        val v = AppVisibility()
        v.onActivityStopped()
        v.onActivityStopped()
        assertTrue(v.onActivityStarted()) // fresh 0->1 transition still reported
        assertFalse(v.onActivityStarted())
    }
}
