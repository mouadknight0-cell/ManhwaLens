package com.manhwalens.app.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Part C / Part E (6) — the floating lens menu state machine: Live toggle, the 4 s
 * auto-close, tap-outside, drag, and "Stop ManhwaLens".
 */
class LensMenuModelTest {

    private class Harness(autoCloseMs: Long = LensMenuModel.AUTO_CLOSE_MS) {
        var now = 50_000L
        val model = LensMenuModel(autoCloseMs) { now }
        fun advance(ms: Long) { now += ms }
        fun open(liveOn: Boolean = false, arabicOn: Boolean = true, review: Boolean = false) =
            model.open(liveOn, arabicOn, review)
    }

    @Test
    fun opensWithTheCurrentStateAndShowsItInTheHeader() {
        val h = Harness()
        val events = h.open(liveOn = true)
        assertEquals(listOf(LensMenuModel.Event.Shown), events)
        assertTrue(h.model.isOpen)
        assertTrue(h.model.liveOn)
        assertEquals("live_on", h.model.stateLabelKey())

        val h2 = Harness()
        h2.open(liveOn = false)
        assertEquals("live_off", h2.model.stateLabelKey())
    }

    @Test
    fun tapOnTheLensTogglesTheMenu() {
        val h = Harness()
        assertEquals(listOf(LensMenuModel.Event.Shown), h.model.toggle(true, true, false))
        assertEquals(
            listOf(LensMenuModel.Event.Hidden),
            h.model.toggle(true, true, false)
        )
        assertFalse(h.model.isOpen)
        assertEquals(LensMenuModel.CloseReason.TOGGLE_FROM_OPEN, h.model.lastCloseReason)
    }

    @Test
    fun liveSwitchEmitsAToggleAndKeepsTheMenuOpenSoTheStateIsVisible() {
        val h = Harness()
        h.open(liveOn = false)
        val events = h.model.onLiveSwitch(true)
        assertEquals(listOf(LensMenuModel.Event.LiveToggled(true)), events)
        assertTrue("the menu stays open after a Live toggle", h.model.isOpen)
        assertTrue(h.model.liveOn)
        assertEquals("live_on", h.model.stateLabelKey())

        val off = h.model.onLiveSwitch(false)
        assertEquals(listOf(LensMenuModel.Event.LiveToggled(false)), off)
        assertFalse(h.model.liveOn)
        assertEquals("live_off", h.model.stateLabelKey())
    }

    @Test
    fun autoClosesAfterFourSecondsOfInactivity() {
        val h = Harness()
        h.open()
        assertEquals(4_000L, LensMenuModel.AUTO_CLOSE_MS)

        h.advance(3_999L)
        assertEquals(emptyList<LensMenuModel.Event>(), h.model.tick(h.now))
        assertTrue(h.model.isOpen)
        assertEquals(1L, h.model.msUntilAutoClose(h.now))

        h.advance(1L)
        assertEquals(listOf(LensMenuModel.Event.Hidden), h.model.tick(h.now))
        assertFalse(h.model.isOpen)
        assertEquals(LensMenuModel.CloseReason.AUTO_CLOSE, h.model.lastCloseReason)
        assertEquals(1, h.model.autoCloseCount)
    }

    @Test
    fun anyTouchInsideTheMenuResetsTheAutoCloseTimer() {
        val h = Harness()
        h.open()
        h.advance(3_000L)
        h.model.touch()                       // user reads the menu, touches a row area
        h.advance(3_000L)
        assertTrue("the timer restarted, so it must still be open", h.model.isOpen)
        assertEquals(emptyList<LensMenuModel.Event>(), h.model.tick(h.now))
        h.advance(1_000L)
        assertEquals(listOf(LensMenuModel.Event.Hidden), h.model.tick(h.now))
    }

    @Test
    fun tapOutsideClosesIt() {
        val h = Harness()
        h.open()
        assertEquals(listOf(LensMenuModel.Event.Hidden), h.model.onOutsideTap())
        assertFalse(h.model.isOpen)
        assertEquals(LensMenuModel.CloseReason.OUTSIDE_TAP, h.model.lastCloseReason)
    }

    @Test
    fun dragClosesItSoItNeverFollowsTheLens() {
        val h = Harness()
        h.open()
        assertEquals(listOf(LensMenuModel.Event.Hidden), h.model.onDragStarted())
        assertEquals(LensMenuModel.CloseReason.DRAG, h.model.lastCloseReason)
    }

    @Test
    fun translateNowClosesTheMenuAndWorksRegardlessOfLiveState() {
        for (liveOn in listOf(true, false)) {
            val h = Harness()
            h.open(liveOn = liveOn)
            val events = h.model.onTranslateNow()
            assertEquals(
                listOf(LensMenuModel.Event.Hidden, LensMenuModel.Event.TranslateNow),
                events
            )
            assertFalse(h.model.isOpen)
            assertEquals("Live stays exactly as it was", liveOn, h.model.liveOn)
        }
    }

    @Test
    fun arabicSwitchTogglesOriginalVsArabicWithoutClosing() {
        val h = Harness()
        h.open(arabicOn = true)
        assertEquals(listOf(LensMenuModel.Event.ArabicToggled(false)), h.model.onArabicSwitch(false))
        assertFalse(h.model.arabicOn)
        assertTrue(h.model.isOpen)
        assertEquals(listOf(LensMenuModel.Event.ArabicToggled(true)), h.model.onArabicSwitch(true))
        assertTrue(h.model.arabicOn)
    }

    @Test
    fun clearTranslationClosesAndEmitsOneEvent() {
        val h = Harness()
        h.open()
        assertEquals(
            listOf(LensMenuModel.Event.Hidden, LensMenuModel.Event.ClearTranslation),
            h.model.onClearTranslation()
        )
        assertFalse(h.model.isOpen)
        assertEquals(LensMenuModel.CloseReason.ROW_ACTION, h.model.lastCloseReason)
    }

    @Test
    fun stopEverythingClosesTheMenuAndAsksForAFullTeardown() {
        val h = Harness()
        h.open(liveOn = true)
        val events = h.model.onStopEverything()
        assertEquals(
            listOf(LensMenuModel.Event.Hidden, LensMenuModel.Event.StopEverything),
            events
        )
        assertFalse(h.model.isOpen)
        assertEquals(LensMenuModel.CloseReason.STOP, h.model.lastCloseReason)
        assertEquals(1, h.model.stopRequestCount)
        // A second request after a re-open is counted too (the service tears everything down).
        h.open(liveOn = true)
        h.model.onStopEverything()
        assertEquals(2, h.model.stopRequestCount)
    }

    @Test
    fun settingsAndReviewRowsNavigateAwayAndCloseTheMenu() {
        val h = Harness()
        h.open(review = true)
        assertTrue(h.model.reviewAvailable)
        assertEquals(
            listOf(LensMenuModel.Event.Hidden, LensMenuModel.Event.OpenSettings),
            h.model.onOpenSettings()
        )
        h.open(review = true)
        assertEquals(
            listOf(LensMenuModel.Event.Hidden, LensMenuModel.Event.ReviewCapture),
            h.model.onReviewCapture()
        )
    }

    @Test
    fun closedMenuIgnoresInteractionAndCloseRequests() {
        val h = Harness()
        h.model.touch()
        assertFalse(h.model.isOpen)
        assertEquals(emptyList<LensMenuModel.Event>(), h.model.close(LensMenuModel.CloseReason.OUTSIDE_TAP))
        assertEquals(emptyList<LensMenuModel.Event>(), h.model.tick(h.now))
        assertFalse(h.model.shouldAutoClose(h.now))
    }

    @Test
    fun syncReflectsTheRealServiceStateAfterConsent() {
        val h = Harness()
        h.open(liveOn = false)
        // Live ON was requested but consent is pending → the service state is still OFF.
        h.model.sync(liveOn = false, arabicOn = true)
        assertEquals("live_off", h.model.stateLabelKey())
        h.model.sync(liveOn = true, arabicOn = false)
        assertEquals("live_on", h.model.stateLabelKey())
        assertFalse(h.model.arabicOn)
    }
}
