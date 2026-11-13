package com.manhwalens.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * M4b+ settings: everything the user can tune from the Settings screen.
 * SharedPreferences on purpose (matches OverlayPrefs); DataStore migration
 * is postponed.
 *
 * Hardening (2026-11-12): EVERY read goes through a safe getter (default +
 * try/catch for wrong-typed/corrupt values), and the store carries a
 * `settings_version` — a one-shot migration wipes corrupt or unknown keys
 * instead of crashing (see SettingsMigration and DECISIONS.md). The store may
 * also be unavailable entirely (OEM weirdness, disk corruption): reads then
 * fall back to defaults and writes are dropped silently. Never throws.
 */
class SettingsPrefs(context: Context) {

    private val prefs: SharedPreferences? = try {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (t: Throwable) {
        Log.e("ManhwaLens", "settings store unavailable — reads fall back to defaults", t)
        null
    }

    init { migrateIfNeeded() }

    // ---- safe primitive readers/writers (never throw) ----
    private fun getBoolean(key: String, def: Boolean): Boolean =
        try { prefs?.getBoolean(key, def) ?: def } catch (t: Throwable) { Log.e("ManhwaLens", "settings read failed ($key) — using default", t); def }

    private fun getFloat(key: String, def: Float): Float =
        try { prefs?.getFloat(key, def) ?: def } catch (t: Throwable) { Log.e("ManhwaLens", "settings read failed ($key) — using default", t); def }

    private fun getInt(key: String, def: Int): Int =
        try { prefs?.getInt(key, def) ?: def } catch (t: Throwable) { Log.e("ManhwaLens", "settings read failed ($key) — using default", t); def }

    private fun getString(key: String, def: String): String =
        try { prefs?.getString(key, def) ?: def } catch (t: Throwable) { Log.e("ManhwaLens", "settings read failed ($key) — using default", t); def }

    private fun edit(block: SharedPreferences.Editor.() -> Unit) {
        try {
            val e = prefs?.edit() ?: return
            e.block()
            e.apply()
        } catch (t: Throwable) {
            Log.e("ManhwaLens", "settings write failed", t)
        }
    }

    /** One-shot migration: validate the whole store, reset corrupt/unknown keys, stamp the version. */
    private fun migrateIfNeeded() {
        try {
            val p = prefs ?: return
            val all: Map<String, Any?> = try {
                p.all ?: emptyMap()
            } catch (t: Throwable) {
                Log.e("ManhwaLens", "settings dump failed — skipping migration", t); return
            }
            val from = SettingsMigration.storedVersion(all)
            if (from >= SettingsMigration.CURRENT_VERSION) return
            val result = SettingsMigration.migrate(all, from)
            p.edit().apply {
                for (k in result.wipe) remove(k)
                putInt(SettingsMigration.KEY_SETTINGS_VERSION, SettingsMigration.CURRENT_VERSION)
            }.apply()
            if (result.wipe.isNotEmpty()) {
                Log.w("ManhwaLens", "settings migrated v$from→v${SettingsMigration.CURRENT_VERSION}: reset keys=${result.wipe.joinToString()}")
            }
        } catch (t: Throwable) {
            Log.e("ManhwaLens", "settings migration failed — defaults will be used for broken keys", t)
        }
    }

    /** Multiplier applied to the auto-fit font size when drawing Arabic in bubbles. */
    var fontScale: Float
        get() = getFloat(KEY_FONT_SCALE, 1.0f)
        set(value) = edit { putFloat(KEY_FONT_SCALE, value.coerceIn(0.7f, 1.5f)) }

    /** Auto-capture a frame shortly after the user stops scrolling (accessibility opt-in). */
    var autoCaptureOnScroll: Boolean
        get() = getBoolean(KEY_AUTO_SCROLL, false)
        set(value) = edit { putBoolean(KEY_AUTO_SCROLL, value) }

    /** Debug mode: show the pipeline trace panel after every tap + save captures with OCR boxes. */
    var debugMode: Boolean
        get() = getBoolean(KEY_DEBUG_MODE, false)
        set(value) = edit { putBoolean(KEY_DEBUG_MODE, value) }

    /** Ignore blocks in the top [ignoreTopPct]% of the frame (browser chrome etc.). Default 8. */
    var ignoreTopPct: Float
        get() = getFloat(KEY_IGNORE_TOP, 8f)
        set(value) = edit { putFloat(KEY_IGNORE_TOP, value.coerceIn(0f, 30f)) }

    /** Ignore blocks in the bottom [ignoreBottomPct]% of the frame.
     * Default 13 (nav bar + 160 px on 720x1600 @1x, density-scaled). */
    var ignoreBottomPct: Float
        get() = getFloat(KEY_IGNORE_BOTTOM, 13f)
        set(value) = edit { putFloat(KEY_IGNORE_BOTTOM, value.coerceIn(0f, 30f)) }

    /** Live mode: watch frames at ~3 fps, translate automatically when the page settles. Default OFF until first lens tap auto-arms it. */
    var liveMode: Boolean
        get() = getBoolean(KEY_LIVE_MODE, false)
        set(value) = edit { putBoolean(KEY_LIVE_MODE, value) }

    /**
     * User explicitly turned live mode OFF (Settings switch or long-press). While set,
     * the service does NOT auto-re-arm live on taps. Cleared by a manual enable.
     */
    var liveOptOut: Boolean
        get() = getBoolean(KEY_LIVE_OPT_OUT, false)
        set(value) = edit { putBoolean(KEY_LIVE_OPT_OUT, value) }

    /**
     * Optional allow-list of foreground package names (comma separated). Empty = live
     * works everywhere. When non-empty, live mode pauses unless the foreground app is
     * in the list (checked via UsageStatsManager — needs the usage-access permission).
     */
    var liveAllowedApps: String
        get() = getString(KEY_LIVE_APPS, "")
        set(value) = edit { putString(KEY_LIVE_APPS, value.trim().take(600)) }

    /**
     * AI cleanup (cloud, experimental): after a tap-translate pass, replace the
     * ≤3 largest bubbles' patches with a Gemini-generated Arabic bubble image.
     * Default OFF. Translate-now only — never Live. Requires a Gemini key.
     */
    var aiCleanupEnabled: Boolean
        get() = getBoolean(KEY_AI_CLEANUP_ENABLED, false)
        set(value) = edit { putBoolean(KEY_AI_CLEANUP_ENABLED, value) }

    /**
     * Model chosen for AI cleanup, picked from the account's models list.
     * "" = auto (first image-capable model from the list at call time).
     */
    var aiCleanupModelId: String
        get() = getString(KEY_AI_CLEANUP_MODEL, "")
        set(value) = edit { putString(KEY_AI_CLEANUP_MODEL, value.trim().take(120)) }

    /**
     * Part C (v0.9.4): what a single tap on the floating lens does.
     * [TAP_MENU] (default) opens the compact ON/OFF menu next to the lens;
     * [TAP_TRANSLATE] is the pre-0.9.4 one-shot "translate now" behaviour.
     * Capture consent is ALWAYS requested first when no session exists (Part B).
     */
    var lensTapMode: String
        get() = getString(KEY_LENS_TAP_MODE, TAP_MENU)
            .let { if (it == TAP_MENU || it == TAP_TRANSLATE) it else TAP_MENU }
        set(value) = edit {
            putString(KEY_LENS_TAP_MODE, if (value == TAP_TRANSLATE) TAP_TRANSLATE else TAP_MENU)
        }

    /** Stability window for live mode (ms). 200..1000, default 400. */
    var liveStableWindowMs: Int
        get() = getInt(KEY_LIVE_STABLE_MS, 400).coerceIn(200, 1000)
        set(value) = edit { putInt(KEY_LIVE_STABLE_MS, value.coerceIn(200, 1000)) }

    /** Change sensitivity for live mode: low | medium | high (default medium). */
    var liveSensitivity: String
        get() = getString(KEY_LIVE_SENSITIVITY, SENS_MEDIUM)
            .let { if (it == SENS_LOW || it == SENS_HIGH || it == SENS_MEDIUM) it else SENS_MEDIUM }
        set(value) = edit {
            putString(
                KEY_LIVE_SENSITIVITY,
                if (value == SENS_LOW || value == SENS_HIGH) value else SENS_MEDIUM
            )
        }

    /** Mean-abs-diff change threshold implied by [liveSensitivity]. High sensitivity → low threshold. */
    fun liveChangeThreshold(): Float = when (liveSensitivity) {
        SENS_LOW -> 16f
        SENS_HIGH -> 6f
        else -> 10f
    }

    /**
     * User-editable ignore phrases (newline/comma separated, case-insensitive substring
     * match). Blocks containing any phrase are skipped before translation. Blank = off.
     */
    var ignorePhrases: String
        get() = getString(KEY_IGNORE_PHRASES, DEFAULT_IGNORE_PHRASES)
        set(value) = edit { putString(KEY_IGNORE_PHRASES, value.take(1200)) }

    /** Parsed non-empty ignore phrases, trimmed, lowercased. */
    fun ignorePhraseList(): List<String> =
        ignorePhrases.split('\n', ',').map { it.trim().lowercase() }.filter { it.length >= 3 }

    companion object {
        const val SMALL = 0.85f
        const val MEDIUM = 1.0f
        const val LARGE = 1.2f
        private const val PREFS_NAME = "manhwalens_settings"
        private const val KEY_FONT_SCALE = SettingsMigration.FONT_SCALE
        private const val KEY_AUTO_SCROLL = SettingsMigration.AUTO_SCROLL
        private const val KEY_DEBUG_MODE = SettingsMigration.DEBUG_MODE
        private const val KEY_IGNORE_TOP = SettingsMigration.IGNORE_TOP
        private const val KEY_IGNORE_BOTTOM = SettingsMigration.IGNORE_BOTTOM
        private const val KEY_LIVE_MODE = SettingsMigration.LIVE_MODE
        private const val KEY_LIVE_OPT_OUT = SettingsMigration.LIVE_OPT_OUT
        private const val KEY_LIVE_APPS = SettingsMigration.LIVE_APPS
        private const val KEY_LIVE_STABLE_MS = SettingsMigration.LIVE_STABLE_MS
        private const val KEY_LIVE_SENSITIVITY = SettingsMigration.LIVE_SENSITIVITY
        private const val KEY_IGNORE_PHRASES = SettingsMigration.IGNORE_PHRASES
        private const val KEY_AI_CLEANUP_ENABLED = SettingsMigration.AI_CLEANUP_ENABLED
        private const val KEY_AI_CLEANUP_MODEL = SettingsMigration.AI_CLEANUP_MODEL
        private const val KEY_LENS_TAP_MODE = SettingsMigration.LENS_TAP_MODE

        /** Tap on the lens → open the compact menu (v0.9.4 default). */
        const val TAP_MENU = "menu"
        /** Tap on the lens → one-shot capture + translate (pre-0.9.4 behaviour). */
        const val TAP_TRANSLATE = "translate"

        const val SENS_LOW = "low"
        const val SENS_MEDIUM = "medium"
        const val SENS_HIGH = "high"

        const val DEFAULT_IGNORE_PHRASES = "Subscribe\nGet the app\nContinue Reading\nTOP\nEpisode\nviews\ncoins"
    }
}
