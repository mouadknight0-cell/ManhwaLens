package com.manhwalens.app.capture

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.manhwalens.app.data.SettingsPrefs
import com.manhwalens.app.overlay.FloatingButtonService

/**
 * Reports when the user stops scrolling any app, so ManhwaLens can capture the
 * newly visible page without the user tapping the lens.
 *
 * Design rules (privacy-critical):
 *  - READ-ONLY scroll events (`typeViewScrolled`); never reads screen content
 *    (`canRetrieveWindowContent="false"`).
 *  - Does nothing unless the user opted in (Settings → Auto-capture on scroll)
 *    AND a capture session is already armed (projection alive).
 *  - Rate-limited to one trigger per [MIN_INTERVAL_MS] to survive fast scrolling.
 */
class ManhwaAutoCaptureService : AccessibilityService() {

    private var lastTriggerAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return
        if (!SettingsPrefs(this).autoCaptureOnScroll) return
        if (FloatingButtonService.instance == null) {
            Log.d(TAG, "scroll seen but no armed capture session; ignoring")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastTriggerAt < MIN_INTERVAL_MS) return
        lastTriggerAt = now
        Log.i(TAG, "scroll finished — triggering auto capture")
        FloatingButtonService.requestAutoCapture()
    }

    override fun onInterrupt() = Unit

    companion object {
        private const val TAG = "MLCapture"
        /** Minimum gap between two auto captures. */
        private const val MIN_INTERVAL_MS = 4_000L
    }
}
