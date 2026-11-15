package com.manhwalens.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Minimal persistence for M1. Uses SharedPreferences on purpose: zero extra
 * dependencies on a very constrained build box. All reads are safe (defaults on
 * corrupt values / unavailable store) — see SafePrefs.
 */
class OverlayPrefs(context: Context) {

    private val prefs: SharedPreferences? = try {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (t: Throwable) {
        Log.e("ManhwaLens", "overlay prefs unavailable — defaults will be used", t)
        null
    }

    private fun getInt(key: String, def: Int): Int =
        try { prefs?.getInt(key, def) ?: def } catch (t: Throwable) { Log.e("ManhwaLens", "overlay pref read failed ($key)", t); def }

    private fun getBoolean(key: String, def: Boolean): Boolean =
        try { prefs?.getBoolean(key, def) ?: def } catch (t: Throwable) { Log.e("ManhwaLens", "overlay pref read failed ($key)", t); def }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        try {
            val e = prefs?.edit() ?: return
            e.block()
            e.apply()
        } catch (t: Throwable) {
            Log.e("ManhwaLens", "overlay pref write failed", t)
        }
    }

    /** Last horizontal position (absolute px, from left edge). -1 = not set. */
    var buttonX: Int
        get() = getInt(KEY_BTN_X, -1)
        set(value) = edit { putInt(KEY_BTN_X, value) }

    /** Last vertical position (absolute px, from top edge). -1 = not set. */
    var buttonY: Int
        get() = getInt(KEY_BTN_Y, -1)
        set(value) = edit { putInt(KEY_BTN_Y, value) }

    /** Whether the user asked the floating button to be shown. */
    var enabled: Boolean
        get() = getBoolean(KEY_ENABLED, false)
        set(value) = edit { putBoolean(KEY_ENABLED, value) }

    /** True while a MediaProjection session is alive and can grab frames. */
    var captureReady: Boolean
        get() = getBoolean(KEY_CAPTURE_READY, false)
        set(value) = edit { putBoolean(KEY_CAPTURE_READY, value) }

    fun savePosition(x: Int, y: Int) = edit { putInt(KEY_BTN_X, x).putInt(KEY_BTN_Y, y) }

    companion object {
        /**
         * One-shot signal set by the service when the foreground service cannot
         * start (or overlay permission is revoked at start). MainActivity collects
         * it to flip the floating-button toggle OFF instead of leaving it ON.
         */
        val serviceFailure = MutableStateFlow<String?>(null)

        private const val PREFS_NAME = "manhwalens_overlay"
        private const val KEY_BTN_X = "btn_x"
        private const val KEY_BTN_Y = "btn_y"
        private const val KEY_ENABLED = "button_enabled"
        private const val KEY_CAPTURE_READY = "capture_ready"
    }
}
