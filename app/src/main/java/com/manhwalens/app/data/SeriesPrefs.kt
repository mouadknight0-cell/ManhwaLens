package com.manhwalens.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.manhwalens.app.names.KeyValueStore

/**
 * SharedPreferences-backed [KeyValueStore] for the series catalogue (P1).
 *
 * A separate prefs file on purpose: the series selection and catalogue must be readable and
 * writable without touching `SettingsPrefs` / `TranslationPrefs` (and a corrupt catalogue can
 * be wiped without losing the reader's glossary, engine choice or API key).
 *
 * Every accessor is guarded — a prefs failure returns the default instead of crashing the
 * translation pipeline (the established pattern in [SafePrefs] / [TranslationPrefs]).
 */
class SeriesPrefs(context: Context) : KeyValueStore {

    private val prefs: SharedPreferences? = try {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (t: Throwable) {
        Log.e("ManhwaLens", "series prefs unavailable — names stay session-only", t)
        null
    }

    override fun getString(key: String, def: String): String =
        try {
            prefs?.getString(key, def) ?: def
        } catch (t: Throwable) {
            Log.e("ManhwaLens", "series pref read failed ($key)", t)
            def
        }

    override fun putString(key: String, value: String) {
        try {
            prefs?.edit()?.putString(key, value)?.apply()
        } catch (t: Throwable) {
            Log.e("ManhwaLens", "series pref write failed ($key)", t)
        }
    }

    override fun getLong(key: String, def: Long): Long =
        try {
            prefs?.getLong(key, def) ?: def
        } catch (t: Throwable) {
            Log.e("ManhwaLens", "series pref read failed ($key)", t)
            def
        }

    override fun putLong(key: String, value: Long) {
        try {
            prefs?.edit()?.putLong(key, value)?.apply()
        } catch (t: Throwable) {
            Log.e("ManhwaLens", "series pref write failed ($key)", t)
        }
    }

    companion object {
        /** Own file — never mixed with [SettingsPrefs] or [TranslationPrefs]. */
        const val PREFS_NAME = "manhwalens_series"
    }
}
