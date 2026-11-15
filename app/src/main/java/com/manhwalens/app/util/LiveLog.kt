package com.manhwalens.app.util

import android.util.Log
import com.manhwalens.app.live.LiveLogRing

/**
 * Logging funnel for the Live path (v0.9.5 · Parts 1f + 2).
 *
 * Every line goes to logcat AND into a 60-entry in-memory [LiveLogRing], so the self-check
 * "Copy report" always has the recent history even when `logcat` is not readable by the app
 * (no READ_LOGS). Messages are key-scrubbed before they reach the ring.
 *
 * [swallowed] is the fix for Part 1f: no `runCatching {}` / `catch (Throwable)` inside the
 * live path may disappear silently — class, message and stack are logged with tag ManhwaLens.
 */
object LiveLog {

    const val TAG = "ManhwaLens"
    const val CAPTURE_TAG = "MLCapture"

    val ring = LiveLogRing(LiveLogRing.DEFAULT_CAPACITY)

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        ring.add("I", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        ring.add("W", tag, message)
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        if (t != null) Log.e(tag, message, t) else Log.e(tag, message)
        ring.add("E", tag, if (t != null) "$message — ${t.javaClass.name}: ${t.message}" else message)
    }

    /**
     * An exception that a `runCatching`/`catch` block absorbed inside the live path.
     * Logs class + message + stack (tag ManhwaLens) and keeps a one-line summary in the ring.
     */
    fun swallowed(where: String, t: Throwable) {
        Log.e(TAG, "live: swallowed exception in $where — ${t.javaClass.name}: ${t.message}", t)
        ring.add("E", TAG, "swallowed in $where — ${t.javaClass.name}: ${t.message}")
    }

    /** The last [n] ring lines as plain text (scrubbed) for the report. */
    fun recentLines(n: Int = LiveLogRing.DEFAULT_CAPACITY): List<String> =
        ring.snapshot().takeLast(n).map { it.line() }
}
