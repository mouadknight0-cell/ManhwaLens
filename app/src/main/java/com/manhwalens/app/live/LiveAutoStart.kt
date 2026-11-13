package com.manhwalens.app.live

/**
 * Pure-JVM policy for WHEN the Live loop may start, what a lens tap does, and when
 * the user's last Live setting may be restored (unit-testable, no Android).
 *
 * The live loop must not exist before a capture session (MediaProjection) is armed
 * — i.e. the first lens tap (or a granted consent) is what brings Live to life,
 * NOT app startup. Settings are only *restored* at that moment.
 *
 * v0.9.4 (Part B): [tapAction] makes capture consent the FIRST check. v0.9.3 read
 * the persisted `liveMode` before looking at the projection, so a tap with
 * `liveMode=true` and no session went to the live handler and silently did nothing
 * — the consent dialog was never shown.
 */
object LiveAutoStart {

    /** What the user configured "Tap on the lens" to do. */
    enum class TapMode(val wire: String) {
        /** Open the compact lens menu next to the button (v0.9.4 default). */
        MENU("menu"),

        /** One-shot capture + translate (the pre-0.9.4 behaviour). */
        TRANSLATE_NOW("translate");

        companion object {
            fun fromWire(value: String?): TapMode =
                entries.firstOrNull { it.wire == value } ?: MENU
        }
    }

    /** The single thing a lens tap must do. Consent always wins. */
    enum class TapAction { CONSENT, MENU, TRANSLATE_NOW }

    /** The loop may start only once a capture session is ready. App launch never qualifies. */
    fun shouldStartLoop(projectionReady: Boolean): Boolean = projectionReady

    /** Restore the persisted liveMode only after the session is armed (never at launch, never past an opt-out). */
    fun shouldRestoreLiveSetting(projectionReady: Boolean, liveMode: Boolean, optOut: Boolean): Boolean =
        projectionReady && liveMode && !optOut

    /**
     * Decide what a lens tap does.
     *
     * `projectionReady == false` → [TapAction.CONSENT] no matter what else is set:
     * without a MediaProjection nothing can be captured, so asking for consent is
     * always the first step (this is the v0.9.3 root cause #2 fix).
     *
     * [liveMode] deliberately does NOT change the outcome: Live is switched from the
     * menu / long-press / notification, and the menu is where its state is visible.
     *
     * @param tapMode the user's "Tap on the lens" setting (Menu | Translate now).
     */
    fun tapAction(projectionReady: Boolean, liveMode: Boolean, tapMode: TapMode): TapAction =
        if (!projectionReady) TapAction.CONSENT
        else when (tapMode) {
            TapMode.MENU -> TapAction.MENU
            TapMode.TRANSLATE_NOW -> TapAction.TRANSLATE_NOW
        }

    /**
     * Live ON was requested (tap with persisted liveMode, long-press toggle, menu
     * switch, notification action) while no capture session exists → consent first,
     * then remember the intent so Live switches on as soon as the session is armed.
     */
    fun needsConsentBeforeLiveEnable(projectionReady: Boolean): Boolean = !projectionReady

    /**
     * What a lens tap must remember before it asks for consent (v0.9.5 Part 3).
     *
     * Granting capture consent is the moment Live becomes possible, so a tap restores the
     * product's original "auto-arm on first tap" rule UNLESS the user explicitly turned
     * Live off ([liveOptOut] — set by every Live OFF path). [requestedLiveEnable] is true
     * when the user actively asked for Live ON (long-press toggle, menu switch, notification).
     */
    fun pendingLiveEnableOnTap(liveMode: Boolean, liveOptOut: Boolean, requestedLiveEnable: Boolean = false): Boolean =
        requestedLiveEnable || !liveOptOut

    /** What to do the moment consent lands and the session exists. */
    data class AfterConsent(
        /** Set `liveMode = true`, `liveOptOut = false`, start the loop, status WAITING. */
        val enableLive: Boolean,
        /** Honour the tap that started all this: show the lens menu. */
        val openMenu: Boolean,
        /** Honour a "Translate now" tap with a one-shot capture. */
        val translateNow: Boolean,
        val reason: String
    )

    /**
     * Post-consent plan (v0.9.5 Part 3): "after consent, set liveMode=true, liveOptOut=false
     * and start the loop" — plus replay the tap action the user actually asked for, so the
     * flow never dead-ends at the consent dialog.
     */
    fun afterConsent(
        tapMode: TapMode,
        requestedLiveEnable: Boolean,
        liveMode: Boolean,
        liveOptOut: Boolean
    ): AfterConsent {
        val enableLive = requestedLiveEnable || (liveMode && !liveOptOut)
        val menu = tapMode == TapMode.MENU
        // When Live is being armed the loop translates within ~2 s by itself — a one-shot
        // capture on top of it would only steal the frame and duplicate the work.
        val oneShot = !enableLive && tapMode == TapMode.TRANSLATE_NOW
        return AfterConsent(
            enableLive = enableLive,
            openMenu = menu,
            translateNow = oneShot,
            reason = "enableLive=$enableLive openMenu=$menu translateNow=$oneShot " +
                "(requested=$requestedLiveEnable liveMode=$liveMode optOut=$liveOptOut tapMode=${tapMode.wire})"
        )
    }

    /**
     * Persisted `liveMode=true` with no session: the lens shows NO_SESSION (grey ring,
     * "Tap to start") instead of pretending Live is running.
     */
    fun showsNoSession(projectionReady: Boolean, liveMode: Boolean): Boolean =
        !projectionReady && liveMode
}
