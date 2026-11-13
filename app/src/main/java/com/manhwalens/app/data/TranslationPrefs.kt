package com.manhwalens.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.manhwalens.app.translate.TranslationEngine
import com.manhwalens.app.translate.gemini.GeminiWire

/**
 * Translation engine settings + the user's Gemini API key.
 *
 * The key lives ONLY in this app's private SharedPreferences on this device.
 * It is sent to Google solely as an HTTP header from the Gemini engine;
 * it is never written to source, logs, or analytics.
 */
class TranslationPrefs(context: Context) {

    private val prefs: SharedPreferences? = try {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    } catch (t: Throwable) {
        Log.e("ManhwaLens", "translation prefs unavailable — defaults will be used", t)
        null
    }

    private fun getString(key: String, def: String): String =
        try { prefs?.getString(key, def) ?: def } catch (t: Throwable) { Log.e("ManhwaLens", "translation pref read failed ($key)", t); def }

    private fun getLong(key: String, def: Long): Long =
        try { prefs?.getLong(key, def) ?: def } catch (t: Throwable) { Log.e("ManhwaLens", "translation pref read failed ($key)", t); def }

    private fun putString(key: String, value: String) {
        try { prefs?.edit()?.putString(key, value)?.apply() } catch (t: Throwable) { Log.e("ManhwaLens", "translation pref write failed ($key)", t) }
    }

    private fun putLong(key: String, value: Long) {
        try { prefs?.edit()?.putLong(key, value)?.apply() } catch (t: Throwable) { Log.e("ManhwaLens", "translation pref write failed ($key)", t) }
    }

    var engine: TranslationEngine
        get() = try {
            val stored = getString(KEY_ENGINE, "").trim()
            val explicitlySet = prefs?.contains(KEY_ENGINE) == true && stored.isNotBlank()
            val engine = when {
                stored.isEmpty() -> TranslationEngine.ML_KIT
                else -> TranslationEngine.valueOf(stored)
            }
            // Part 6: default engine = Gemini when a key exists, unless the user
            // explicitly chose ML Kit. ML Kit remains the automatic fallback.
            if (!explicitlySet && hasGeminiKey) TranslationEngine.GEMINI else engine
        } catch (_: Throwable) {
            if (hasGeminiKey) TranslationEngine.GEMINI else TranslationEngine.ML_KIT
        }
        set(value) = putString(KEY_ENGINE, value.name)

    var geminiKey: String
        get() = getString(KEY_GEMINI_KEY, "")
        set(value) = putString(KEY_GEMINI_KEY, value.trim())

    val hasGeminiKey: Boolean get() = geminiKey.isNotBlank()

    /** Selected Gemini model id; defaults to gemini-3.8-flash. */
    var geminiModel: String
        get() = getString(KEY_GEMINI_MODEL, GeminiWire.DEFAULT_MODEL)
        set(value) = putString(KEY_GEMINI_MODEL, value.trim())

    /** Circuit breaker: epoch-ms until which Gemini is skipped in favour of ML Kit. */
    var geminiDisabledUntilMs: Long
        get() = getLong(KEY_GEMINI_CIRCUIT_UNTIL, 0L)
        set(value) = putLong(KEY_GEMINI_CIRCUIT_UNTIL, value)

    var geminiDisabledReason: String
        get() = getString(KEY_GEMINI_CIRCUIT_REASON, "")
        set(value) = putString(KEY_GEMINI_CIRCUIT_REASON, value.take(140))

    val geminiCircuit: com.manhwalens.app.translate.GeminiCircuit
        get() = com.manhwalens.app.translate.GeminiCircuit(
            disabledUntilMs = { geminiDisabledUntilMs },
            disabledReasonStore = { geminiDisabledReason },
            onDisable = { until, reason ->
                geminiDisabledUntilMs = until
                geminiDisabledReason = reason
            },
            onClear = {
                try {
                    prefs?.edit()
                        ?.remove(KEY_GEMINI_CIRCUIT_UNTIL)
                        ?.remove(KEY_GEMINI_CIRCUIT_REASON)
                        ?.apply()
                } catch (t: Throwable) {
                    Log.e("ManhwaLens", "translation pref clear (circuit) failed", t)
                }
                Unit
            }
        )

    /**
     * Translation style id — namespace for the cache key. The app ships one
     * built-in style ("default"); the key ALWAYS includes it so a future style
     * setting never collides with cached strings from another style.
     */
    var translationStyle: String
        get() = getString(KEY_TRANSLATION_STYLE, "default")
        set(value) = putString(KEY_TRANSLATION_STYLE, value.trim().ifBlank { "default" }.take(40))

    /** User name/term entries, one "English = عربي" per line ([com.manhwalens.app.translate.Glossary]). */
    var glossary: String
        get() = getString(KEY_GLOSSARY, "")
        set(value) = putString(KEY_GLOSSARY, value.take(6000))

    /** User-managed LibreTranslate server URL (HTTPS only). No server is bundled with the app. */
    var libreTranslateUrl: String
        get() = getString(KEY_LIBRE_TRANSLATE_URL, "")
        set(value) = putString(KEY_LIBRE_TRANSLATE_URL, value.trim().take(1024))

    /** Optional API key for that server; sent only in the HTTPS JSON request body. */
    var libreTranslateApiKey: String
        get() = getString(KEY_LIBRE_TRANSLATE_API_KEY, "")
        set(value) = putString(KEY_LIBRE_TRANSLATE_API_KEY, value.trim().take(1024))

    val hasLibreTranslateUrl: Boolean get() = libreTranslateUrl.isNotBlank()

    fun clearLibreTranslateSettings() {
        try {
            prefs?.edit()
                ?.remove(KEY_LIBRE_TRANSLATE_URL)
                ?.remove(KEY_LIBRE_TRANSLATE_API_KEY)
                ?.apply()
        } catch (t: Throwable) {
            Log.e("ManhwaLens", "clear LibreTranslate settings failed", t)
        }
    }

    /** v0.9.14: join the lines of one bubble into a single sentence before translating. */
    var mergeBubbleLines: Boolean
        get() = getLong(KEY_MERGE_LINES, 1L) != 0L
        set(value) = putLong(KEY_MERGE_LINES, if (value) 1L else 0L)

    /** Hand the current glossary to [com.manhwalens.app.translate.Glossary] (called on engine build). */
    fun refreshGlossary() {
        com.manhwalens.app.translate.Glossary.refresh(glossary, mergeBubbleLines)
    }

    /** Key/value seam for [com.manhwalens.app.translate.BatchPolicy] (per-device batching choice + failure memory). */
    val batchStore: com.manhwalens.app.translate.BatchStore
        get() = object : com.manhwalens.app.translate.BatchStore {
            override fun getString(key: String, def: String): String = this@TranslationPrefs.getString(key, def)
            override fun putString(key: String, value: String) = this@TranslationPrefs.putString(key, value)
            override fun getLong(key: String, def: Long): Long = this@TranslationPrefs.getLong(key, def)
            override fun putLong(key: String, value: Long) = this@TranslationPrefs.putLong(key, value)
        }

    fun clearGeminiKey() {
        try { prefs?.edit()?.remove(KEY_GEMINI_KEY)?.apply() } catch (t: Throwable) { Log.e("ManhwaLens", "clear gemini key failed", t) }
    }

    companion object {
        private const val PREFS_NAME = "manhwalens_translation"
        private const val KEY_ENGINE = "engine"
        private const val KEY_GEMINI_KEY = "gemini_api_key"
        private const val KEY_GEMINI_MODEL = "gemini_model"
        private const val KEY_GEMINI_CIRCUIT_UNTIL = "gemini_circuit_until"
        private const val KEY_GEMINI_CIRCUIT_REASON = "gemini_circuit_reason"
        private const val KEY_TRANSLATION_STYLE = "translation_style"
        private const val KEY_GLOSSARY = "glossary_names"
        private const val KEY_LIBRE_TRANSLATE_URL = "libre_translate_url"
        private const val KEY_LIBRE_TRANSLATE_API_KEY = "libre_translate_api_key"
        private const val KEY_MERGE_LINES = "merge_bubble_lines"
    }
}
