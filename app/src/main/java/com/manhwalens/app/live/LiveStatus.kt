package com.manhwalens.app.live

/**
 * Exact live-mode states (Part B) surfaced on the floating button caption and in
 * the debug panel. [wire] is the stable machine name used in logs/trace; [resId]
 * is the localized caption on the button (short) and panel.
 */
enum class LiveStatus(
    val wire: String,
    val dot: Int,
    val captionRes: Int
) {
    /** Live mode turned off / paused by the user or by the service. */
    PAUSED("paused", com.manhwalens.app.overlay.LensButtonView.LIVE_DOT_OFF, com.manhwalens.app.R.string.live_status_paused),
    /** Live on; frame not (yet) different-or-stable — waiting for the page to settle. */
    WAITING("waiting", com.manhwalens.app.overlay.LensButtonView.LIVE_DOT_IDLE, com.manhwalens.app.R.string.live_status_waiting),
    /** Frame is changing — scrolling or animation in progress. */
    SCANNING("scanning", com.manhwalens.app.overlay.LensButtonView.LIVE_DOT_SCANNING, com.manhwalens.app.R.string.live_status_scanning),
    /** OCR/translation in flight for the settled frame. */
    TRANSLATING("translating", com.manhwalens.app.overlay.LensButtonView.LIVE_DOT_TRANSLATING, com.manhwalens.app.R.string.live_status_translating),
    /** Stable AND identical to the already-translated page — nothing to do. */
    SKIPPED("same-page-skipped", com.manhwalens.app.overlay.LensButtonView.LIVE_DOT_IDLE, com.manhwalens.app.R.string.live_status_skipped),
    /** Live is on but no MediaProjection session is armed. */
    NO_SESSION("no-session", com.manhwalens.app.overlay.LensButtonView.LIVE_DOT_NO_SESSION, com.manhwalens.app.R.string.live_status_no_session),
    /** Last live pass failed; [detail] carries the reason. */
    ERROR("error", com.manhwalens.app.overlay.LensButtonView.LIVE_DOT_ERROR, com.manhwalens.app.R.string.live_status_error)
}
