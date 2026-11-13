package com.manhwalens.app.overlay

/**
 * Pure-JVM state machine behind the floating lens menu (v0.9.4 · Part C).
 *
 * [LensMenuWindow] is view code (windows, widgets, touch); every DECISION lives here so
 * it can be unit-tested without Android: what the header shows, when the menu closes
 * itself (4 s of inactivity, reset by any touch inside it), and what each row does.
 *
 * Rows emit [Event]s; the window maps them onto its [LensMenuWindow.Callbacks] and the
 * service executes them (Live toggle → consent-first, Stop → tear everything down, …).
 *
 * No Android types — safe to instantiate in a JVM unit test.
 */
class LensMenuModel(
    private val autoCloseMs: Long = AUTO_CLOSE_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    sealed interface Event {
        /** The menu became visible. */
        data object Shown : Event
        /** The menu must disappear (auto-close, tap outside, drag, row that navigates away). */
        data object Hidden : Event
        /** Live ON/OFF switch — the service asks for capture consent first when needed. */
        data class LiveToggled(val enable: Boolean) : Event
        /** One-shot capture + translate (works with Live OFF). */
        data object TranslateNow : Event
        /** Arabic patches shown / original page. */
        data class ArabicToggled(val showArabic: Boolean) : Event
        /** Remove every patch (reason=user clear). */
        data object ClearTranslation : Event
        /** Open the full-frame review viewer. */
        data object ReviewCapture : Event
        /** Stop the loop, release the session, remove all overlays, stop the FGS. */
        data object StopEverything : Event
        /** Open the app settings screen. */
        data object OpenSettings : Event
    }

    /** Why the menu closed — logged and shown in the debug panel. */
    enum class CloseReason(val wire: String) {
        NONE("none"), AUTO_CLOSE("auto-close"), OUTSIDE_TAP("outside-tap"),
        ROW_ACTION("row-action"), DRAG("drag"), STOP("stop"), TOGGLE_FROM_OPEN("toggle")
    }

    var isOpen: Boolean = false
        private set

    var liveOn: Boolean = false
        private set

    var arabicOn: Boolean = true
        private set

    /** True when a reviewable tap capture exists (row enabled/disabled). */
    var reviewAvailable: Boolean = false
        private set

    var openedAtMs: Long = 0L
        private set

    var lastInteractionAtMs: Long = 0L
        private set

    var lastCloseReason: CloseReason = CloseReason.NONE
        private set

    var autoCloseCount: Int = 0
        private set

    var stopRequestCount: Int = 0
        private set

    /** Header text key: "Live ON" / "Live OFF". */
    fun stateLabelKey(): String = if (liveOn) "live_on" else "live_off"

    /** Open the menu (no-op when it is already open). */
    fun open(liveOn: Boolean, arabicOn: Boolean, reviewAvailable: Boolean): List<Event> {
        if (isOpen) return emptyList()
        val now = clock()
        this.liveOn = liveOn
        this.arabicOn = arabicOn
        this.reviewAvailable = reviewAvailable
        isOpen = true
        openedAtMs = now
        lastInteractionAtMs = now
        lastCloseReason = CloseReason.NONE
        return listOf(Event.Shown)
    }

    /** Tap on the lens: open when closed, close when open. */
    fun toggle(liveOn: Boolean, arabicOn: Boolean, reviewAvailable: Boolean): List<Event> =
        if (isOpen) close(CloseReason.TOGGLE_FROM_OPEN)
        else open(liveOn, arabicOn, reviewAvailable)

    /** Any touch inside the menu resets the auto-close timer. */
    fun touch() {
        if (!isOpen) return
        lastInteractionAtMs = clock()
    }

    /** Close the menu; idempotent. */
    fun close(reason: CloseReason): List<Event> {
        if (!isOpen) return emptyList()
        isOpen = false
        lastCloseReason = reason
        if (reason == CloseReason.AUTO_CLOSE) autoCloseCount++
        return listOf(Event.Hidden)
    }

    /** True once the menu has been idle for [autoCloseMs]. */
    fun shouldAutoClose(nowMs: Long = clock()): Boolean =
        isOpen && nowMs - lastInteractionAtMs >= autoCloseMs

    /** Ms left before the auto-close fires (0 when it is due now). */
    fun msUntilAutoClose(nowMs: Long = clock()): Long =
        if (!isOpen) 0L else (lastInteractionAtMs + autoCloseMs - nowMs).coerceAtLeast(0L)

    /** Periodic check used by the window's timer: closes the menu when it went idle. */
    fun tick(nowMs: Long = clock()): List<Event> =
        if (shouldAutoClose(nowMs)) close(CloseReason.AUTO_CLOSE) else emptyList()

    // ---- rows ----

    fun onLiveSwitch(enable: Boolean): List<Event> {
        touch()
        liveOn = enable
        // The menu stays open so the user can see the new state (and the switch colour).
        return listOf(Event.LiveToggled(enable))
    }

    fun onTranslateNow(): List<Event> {
        val out = ArrayList<Event>()
        out += close(CloseReason.ROW_ACTION)
        out += Event.TranslateNow
        return out
    }

    fun onArabicSwitch(showArabic: Boolean): List<Event> {
        touch()
        arabicOn = showArabic
        return listOf(Event.ArabicToggled(showArabic))
    }

    fun onClearTranslation(): List<Event> {
        val out = ArrayList<Event>()
        out += close(CloseReason.ROW_ACTION)
        out += Event.ClearTranslation
        return out
    }

    fun onReviewCapture(): List<Event> {
        val out = ArrayList<Event>()
        out += close(CloseReason.ROW_ACTION)
        out += Event.ReviewCapture
        return out
    }

    fun onStopEverything(): List<Event> {
        stopRequestCount++
        val out = ArrayList<Event>()
        out += close(CloseReason.STOP)
        out += Event.StopEverything
        return out
    }

    fun onOpenSettings(): List<Event> {
        val out = ArrayList<Event>()
        out += close(CloseReason.ROW_ACTION)
        out += Event.OpenSettings
        return out
    }

    /** A drag starts → the menu must never follow the lens around. */
    fun onDragStarted(): List<Event> = close(CloseReason.DRAG)

    /** A tap landed outside the menu. */
    fun onOutsideTap(): List<Event> = close(CloseReason.OUTSIDE_TAP)

    /** Refresh the switches from the real service state (e.g. after consent was requested). */
    fun sync(liveOn: Boolean, arabicOn: Boolean) {
        this.liveOn = liveOn
        this.arabicOn = arabicOn
    }

    companion object {
        /** Part C: the menu closes itself after 4 s of inactivity. */
        const val AUTO_CLOSE_MS = 4_000L
    }
}
