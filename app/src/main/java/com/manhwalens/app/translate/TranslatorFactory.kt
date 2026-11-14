package com.manhwalens.app.translate

import android.content.Context
import com.manhwalens.app.data.CharacterMemory
import com.manhwalens.app.data.NameSession
import com.manhwalens.app.data.TranslationPrefs
import com.manhwalens.app.translate.gemini.GeminiTranslator
import com.manhwalens.app.translate.gemini.HttpUrlConnectionGeminiApi
import com.manhwalens.app.translate.libre.HttpUrlConnectionLibreTranslateApi
import com.manhwalens.app.translate.libre.LibreTranslateTranslator
import com.manhwalens.app.translate.libre.LibreTranslateWire
import com.manhwalens.app.translate.mlkit.MlKitTranslator
import java.io.File

/** Translator used when Gemini is selected but no key has been saved. */
private class MissingKeyTranslator : Translator {
    override val engineId = "gemini"
    override fun translatePage(blocks: List<TranslationBlock>): PageTranslation =
        PageTranslation.Failure(
            FailureReason.INVALID_API_KEY,
            "Gemini API key is not set — add it in the translation settings",
            engineId
        )
}

/** Translator used when the optional self-hosted engine has no valid HTTPS endpoint. */
private class MissingLibreTranslateEndpoint : Translator {
    override val engineId = LibreTranslateTranslator.ENGINE_ID
    override fun translatePage(blocks: List<TranslationBlock>): PageTranslation =
        PageTranslation.Failure(
            FailureReason.UNKNOWN,
            "Set a valid HTTPS LibreTranslate server URL in the translation settings",
            engineId
        )
}

/**
 * Builds the user's chosen engine on every call (prefs are cheap to read) and
 * shares one process-scoped memory LRU plus one disk LRU between them. Secrets
 * are handed only to the selected HTTP client and never included in cache keys.
 */
object TranslatorFactory {

    private val sharedCache = TranslationCache(maxEntries = 1000)

    @Volatile
    private var diskCache: PersistentTranslationCache? = null

    /**
     * P1 — one name session per translator instance (i.e. per captured page).
     *
     * Built here rather than in [com.manhwalens.app.overlay.FloatingButtonService] so no
     * orchestration code changes: every engine the factory returns is already series-aware.
     * Unassigned sessions (no series selected) resolve names exactly like v0.11.0 and persist
     * nothing.
     */
    fun nameSession(context: Context): NameSession = runCatching {
        CharacterMemory.session(context)
    }.getOrElse {
        // A name-memory failure must never break translation: fall back to an unassigned session.
        com.manhwalens.app.util.ErrorLog.write(context, "names", it)
        NameSession(seriesId = null, seriesTitle = null, store = null)
    }

    /** Shared P1 wiring: series-aware page names + authoritative override for cached text. */
    private fun names(context: Context): NameSession = nameSession(context)

    private fun disk(context: Context): PersistentTranslationCache =
        diskCache ?: PersistentTranslationCache(
            File(context.applicationContext.filesDir, "translation_cache.json"),
            maxEntries = 2000
        ).also { diskCache = it }

    /**
     * Attach the per-device batching policy (joined-vs-parallel choice + 7-day failure
     * memory) once per process. Every factory entry point calls this, so the ML Kit engine
     * always sees it; safe from any thread.
     */
    fun ensureBatchPolicy(context: Context): BatchPolicy {
        BatchPolicyHolder.instance?.let { return it }
        synchronized(this) {
            BatchPolicyHolder.instance?.let { return it }
            val policy = BatchPolicy(
                store = TranslationPrefs(context.applicationContext).batchStore,
                deviceKey = runCatching { android.os.Build.FINGERPRINT }.getOrNull() ?: "unknown"
            )
            BatchPolicyHolder.instance = policy
            return policy
        }
    }

    private fun styleOf(context: Context): String = TranslationPrefs(context).translationStyle

    private fun styleForLibre(prefs: TranslationPrefs): String =
        "${prefs.translationStyle}|libre:${LibreTranslateWire.endpointCacheTag(prefs.libreTranslateUrl) ?: "invalid"}"

    /** Fuzzy memo for one engine+style namespace (short strings only). */
    private fun memo(engineId: String, style: String): TranslationMemo =
        CachingTranslator.memoFor(engineId, style)

    /** Build a cached Gemini translator with the app-private disk cache. */
    private fun gemini(context: Context, prefs: TranslationPrefs): Translator {
        val session = names(context)
        return CachingTranslator(
            GeminiTranslator(
                HttpUrlConnectionGeminiApi(prefs.geminiKey),
                modelProvider = { prefs.geminiModel },
                modelSaver = { prefs.geminiModel = it },
                // P1 / P2-05: one resolved table per page supplies BOTH halves — the sheet text
                // for the prompt and the map for the repair — so the two cannot disagree.
                nameSheetProvider = { texts ->
                    val table = session.tableFor(texts)
                    NameSheet(table.sheet, table.table)
                }
            ),
            sharedCache,
            disk(context),
            prefs.translationStyle,
            memo = memo("gemini", prefs.translationStyle),
            pinnedOverride = session::rewrite
        )
    }

    private fun libre(context: Context, prefs: TranslationPrefs): Translator {
        val session = names(context)
        return CachingTranslator(
            LibreTranslateTranslator(
                api = HttpUrlConnectionLibreTranslateApi(),
                endpointProvider = { prefs.libreTranslateUrl },
                apiKeyProvider = { prefs.libreTranslateApiKey },
                // P2-08: LibreTranslate has no prompt channel; apply the same resolved
                // page/series name table to its source text before the HTTP request.
                nameTableProvider = { texts -> session.tableFor(texts).table }
            ),
            sharedCache,
            disk(context),
            styleForLibre(prefs),
            memo = memo(LibreTranslateTranslator.ENGINE_ID, styleForLibre(prefs)),
            pinnedOverride = session::rewrite
        )
    }

    fun create(context: Context): Translator {
        ensureBatchPolicy(context)
        val prefs = TranslationPrefs(context)
        prefs.refreshGlossary()
        val style = prefs.translationStyle
        val disk = disk(context)
        return when (prefs.engine) {
            TranslationEngine.ML_KIT -> {
                val session = names(context)
                CachingTranslator(
                    MlKitTranslator(nameTableProvider = { texts -> session.tableFor(texts).table }),
                    sharedCache, disk, style, memo = memo("ml_kit_en_ar", style),
                    pinnedOverride = session::rewrite
                )
            }
            TranslationEngine.GEMINI ->
                if (prefs.geminiCircuit.isOpen()) {
                    // Circuit open: do NOT gamble 3+6 s per tap — go straight offline.
                    com.manhwalens.app.util.PipelineTracer.span(
                        "circuit breaker -> ML Kit", 0,
                        "gemini paused (${prefs.geminiCircuit.reason().take(60)})"
                    )
                    val session = names(context)
                    CachingTranslator(
                        MlKitTranslator(nameTableProvider = { texts -> session.tableFor(texts).table }),
                        sharedCache, disk, style, memo = memo("ml_kit_en_ar", style),
                        pinnedOverride = session::rewrite
                    )
                } else if (prefs.hasGeminiKey) {
                    gemini(context, prefs)
                } else {
                    MissingKeyTranslator()
                }
            TranslationEngine.LIBRE_TRANSLATE ->
                if (LibreTranslateWire.translateEndpoint(prefs.libreTranslateUrl) != null) {
                    libre(context, prefs)
                } else {
                    MissingLibreTranslateEndpoint()
                }
        }
    }

    /** ML Kit only (progressive pass): same shared caches, ignores the engine pref. */
    fun createOffline(context: Context): Translator {
        ensureBatchPolicy(context)
        TranslationPrefs(context).refreshGlossary()
        val style = styleOf(context)
        val session = names(context)
        return CachingTranslator(
            MlKitTranslator(nameTableProvider = { texts -> session.tableFor(texts).table }),
            sharedCache, disk(context), style, memo = memo("ml_kit_en_ar", style),
            pinnedOverride = session::rewrite
        )
    }

    /**
     * Progressive display variant: each block is pushed to [onBlockReady]
     * as it completes (per-block ≤3 in flight inside ML Kit), while cache hits
     * are emitted instantly. Used by the tap pipeline.
     */
    fun createProgressive(context: Context, onBlockReady: (TranslatedBlock) -> Unit): Translator {
        ensureBatchPolicy(context)
        TranslationPrefs(context).refreshGlossary()
        val style = styleOf(context)
        val session = names(context)
        return CachingTranslator(
            MlKitTranslator(
                onBlockReady,
                nameTableProvider = { texts -> session.tableFor(texts).table }
            ),
            sharedCache,
            disk(context),
            style,
            onBlockReady = onBlockReady,
            memo = memo("ml_kit_en_ar", style),
            pinnedOverride = session::rewrite
        )
    }

    /**
     * Whether a selected remote engine can run as a best-effort upgrade beside the
     * fast on-device draft. An invalid/missing endpoint never blocks the ML Kit path.
     */
    fun remoteUpgradeAvailable(context: Context): Boolean {
        val prefs = TranslationPrefs(context)
        return when (prefs.engine) {
            TranslationEngine.ML_KIT -> false
            TranslationEngine.GEMINI -> prefs.hasGeminiKey && !prefs.geminiCircuit.isOpen()
            TranslationEngine.LIBRE_TRANSLATE ->
                LibreTranslateWire.translateEndpoint(prefs.libreTranslateUrl) != null
        }
    }

    /**
     * Build ONLY the configured remote engine, or return null when unavailable.
     * It never silently substitutes ML Kit; callers can keep the already-painted draft.
     */
    fun createRemoteOnly(context: Context): Translator? {
        ensureBatchPolicy(context)
        val prefs = TranslationPrefs(context)
        prefs.refreshGlossary()
        return when (prefs.engine) {
            TranslationEngine.ML_KIT -> null
            TranslationEngine.GEMINI ->
                if (prefs.hasGeminiKey && !prefs.geminiCircuit.isOpen()) gemini(context, prefs) else null
            TranslationEngine.LIBRE_TRANSLATE ->
                if (LibreTranslateWire.translateEndpoint(prefs.libreTranslateUrl) != null) libre(context, prefs) else null
        }
    }

    /** Backwards-compatible Gemini-only factory entry point. */
    fun createGeminiOnly(context: Context): Translator? {
        val prefs = TranslationPrefs(context)
        if (prefs.engine != TranslationEngine.GEMINI || !prefs.hasGeminiKey || prefs.geminiCircuit.isOpen()) return null
        prefs.refreshGlossary()
        return gemini(context, prefs)
    }

    fun cacheSize(): Int = sharedCache.size() + (diskCache?.size() ?: 0)

    fun clearCache() {
        sharedCache.clear()
        diskCache?.clear()
        CachingTranslator.resetMemos()
    }
}
