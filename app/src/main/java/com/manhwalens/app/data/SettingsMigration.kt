package com.manhwalens.app.data

/**
 * Settings version + one-shot migration for the `manhwalens_settings` store.
 *
 * Why: after in-place upgrades the app must survive a store that was written by
 * an older build (or corrupted by a process kill mid-write) — a wrong-typed or
 * meaningless value must NEVER reach the UI as a crash, it must be reset to its
 * default. This object is the pure-JVM core of that guarantee; SettingsPrefs
 * applies the outcome to the real SharedPreferences.
 *
 * Rules (see DECISIONS.md):
 *  - known key with the right type           → keep
 *  - known key with a wrong/corrupt type     → wipe (= default)
 *  - unknown key (older/other builds)        → wipe (= reset unknown values)
 *  - settings_version newer than CURRENT     → leave everything untouched
 */
object SettingsMigration {

    /** Bump when a new incompatible key/type is introduced; CURRENT_VERSION-1 is the legacy layout. */
    const val CURRENT_VERSION = 2
    const val KEY_SETTINGS_VERSION = "settings_version"
    private const val LEGACY_VERSION = 1

    // Key names mirrored here (single source of truth for the migration).
    internal const val FONT_SCALE = "font_scale"
    internal const val AUTO_SCROLL = "auto_capture_scroll"
    internal const val DEBUG_MODE = "debug_mode"
    internal const val IGNORE_TOP = "ignore_top_pct"
    internal const val IGNORE_BOTTOM = "ignore_bottom_pct"
    internal const val LIVE_MODE = "live_mode"
    internal const val LIVE_OPT_OUT = "live_opt_out"
    internal const val LIVE_APPS = "live_allowed_apps"
    internal const val LIVE_STABLE_MS = "live_stable_ms"
    internal const val LIVE_SENSITIVITY = "live_sensitivity"
    internal const val IGNORE_PHRASES = "ignore_phrases"
    internal const val AI_CLEANUP_ENABLED = "ai_cleanup_enabled"
    internal const val AI_CLEANUP_MODEL = "ai_cleanup_model_id"
    internal const val LENS_TAP_MODE = "lens_tap_mode"

    private val BOOL_KEYS = setOf(AUTO_SCROLL, DEBUG_MODE, LIVE_MODE, LIVE_OPT_OUT, AI_CLEANUP_ENABLED)
    private val FLOAT_KEYS = setOf(FONT_SCALE, IGNORE_TOP, IGNORE_BOTTOM)
    private val INT_KEYS = setOf(LIVE_STABLE_MS)
    private val STRING_KEYS = setOf(LIVE_APPS, LIVE_SENSITIVITY, IGNORE_PHRASES, AI_CLEANUP_MODEL, LENS_TAP_MODE)
    private val KNOWN_KEYS = BOOL_KEYS + FLOAT_KEYS + INT_KEYS + STRING_KEYS + setOf(KEY_SETTINGS_VERSION)

    fun isKnown(key: String): Boolean = KNOWN_KEYS.contains(key)

    /** Type gate for a stored value. Numbers are tolerated across int/long/float/double. */
    fun typeOk(key: String, value: Any?): Boolean = when {
        value == null -> false
        BOOL_KEYS.contains(key) -> value is Boolean
        FLOAT_KEYS.contains(key) -> value is Float || value is Double || value is Int || value is Long
        INT_KEYS.contains(key) -> value is Int || value is Long || value is Short
        STRING_KEYS.contains(key) -> value is String
        key == KEY_SETTINGS_VERSION -> value is Int || value is Long
        else -> false
    }

    /** Version stored in [all]; a missing key means the legacy (v1) layout. */
    fun storedVersion(all: Map<String, Any?>): Int = SafePrefs.asInt(all[KEY_SETTINGS_VERSION], LEGACY_VERSION)

    data class Result(
        /** Keys to remove from the store (reset to defaults). */
        val wipe: List<String>,
        /** Version to stamp after the migration completes. */
        val newVersion: Int
    )

    /** Pure migration: decide what to wipe for a store dump [all] at [fromVersion]. */
    fun migrate(all: Map<String, Any?>, fromVersion: Int): Result {
        if (fromVersion >= CURRENT_VERSION) {
            // Same-or-newer layout (or a downgrade from the future): never destroy user data.
            return Result(emptyList(), fromVersion)
        }
        val wipe = all.keys.filter { key ->
            when {
                !KNOWN_KEYS.contains(key) -> true               // unknown → reset
                !typeOk(key, all[key]) -> true                  // corrupt/wrong type → reset
                else -> false                                    // valid → keep
            }
        }
        return Result(wipe, CURRENT_VERSION)
    }
}
