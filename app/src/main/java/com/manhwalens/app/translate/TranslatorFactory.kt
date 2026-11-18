package com.manhwalens.app.translate

import android.content.Context
import com.manhwalens.app.data.CharacterMemory
import com.manhwalens.app.data.NameSession
import com.manhwalens.app.data.TranslationPrefs
import com.manhwalens.app.data.SettingsPrefs
import com.manhwalens.app.util.TranslationPairLog
import com.manhwalens.app.translate.gemini.GeminiTranslator
import com.manhwalens.app.translate.gemini.HttpUrlConnectionGeminiApi
import com.manhwalens.app.translate.mlkit.MlKitTranslator
import com.manhwalens.app.translate.mlkit.MlKitTranslationTrace
import com.manhwalens.app.translate.bergamot.BergamotPageTranslator
import com.manhwalens.app.translate.bergamot.BergamotEnArModel
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

/**
 * Builds the user's chosen engine on every call (prefs are cheap to read) and
 * shares one process-scoped memory LRU plus one disk LRU between them. Secrets
 * are handed only to the selected HTTP client and never included in cache keys.
 */
object TranslatorFactory {

    private val sharedCache = TranslationCache(maxEntries = 1000)

    @Volatile
    private var diskCache: PersistentTranslationCache? = null

    /** One native Bergamot service/model per process; recreating it per capture leaks native RAM. */
    @Volatile
    private var bergamotDelegate: BergamotPageTranslator? = null

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

    /** Fuzzy memo for one engine+style namespace (short strings only). */
    private fun memo(engineId: String, style: String): TranslationMemo =
        CachingTranslator.memoFor(engineId, style)

    /** Pair capture is opt-in with Debug mode and remains app-private on this device. */
    private fun pairRecorder(context: Context): ((TranslationPairTrace) -> Unit)? {
        if (!SettingsPrefs(context.applicationContext).debugMode) return null
        val app = context.applicationContext
        return { trace -> TranslationPairLog.record(app, trace) }
    }

    /** Build a cached Gemini translator with the app-private disk cache. */
    private fun gemini(context: Context, prefs: TranslationPrefs): Translator {
        val session = names(context)
        return CachingTranslator(
            GeminiTranslator(
                HttpUrlConnectionGeminiApi(prefs.geminiKey),
                modelProvider = { prefs.geminiModel },
                modelSaver = { prefs.geminiModel = it },
                // Production uses constrained JSON output for stable id→bubble mapping.
                // GeminiTranslator retries once in legacy mode if the API rejects JSON mode.
                jsonReply = true,
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
            pinnedOverride = session::rewrite,
            onTranslationPair = pairRecorder(context)
        )
    }

    fun create(context: Context): Translator {
        ensureBatchPolicy(context)
        val prefs = TranslationPrefs(context)
        prefs.refreshGlossary()
        val style = prefs.translationStyle
        val disk = disk(context)
        // Explicit offline mode is authoritative: never construct or call a cloud translator.
        if (OfflineTranslationPolicy.mustUseLocal(prefs.offlineMode)) {
            return if (prefs.engine == TranslationEngine.BERGAMOT) createBergamot(context) else createOffline(context)
        }
        return when (prefs.engine) {
            TranslationEngine.ML_KIT -> {
                val session = names(context)
                CachingTranslator(
                    MlKitTranslator(nameTableProvider = { texts -> session.tableFor(texts).table }),
                    sharedCache, disk, style, memo = memo("ml_kit_en_ar", style),
                    pinnedOverride = session::rewrite,
            onTranslationPair = pairRecorder(context)
                )
            }
            TranslationEngine.BERGAMOT -> createBergamot(context)
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
                        pinnedOverride = session::rewrite,
            onTranslationPair = pairRecorder(context)
                    )
                } else if (prefs.hasGeminiKey) {
                    gemini(context, prefs)
                } else {
                    MissingKeyTranslator()
                }
        }
    }


    /**
     * Preload the process-wide Bergamot model and warm its native translation path.
     * Settings invokes this on Dispatchers.Default after confirming the model exists.
     */
    fun prewarmBergamot(context: Context) {
        if (!BergamotEnArModel.isInstalled(context)) return
        val delegate = bergamotDelegate ?: synchronized(this) {
            bergamotDelegate ?: BergamotPageTranslator(context).also { bergamotDelegate = it }
        }
        delegate.warmUp()
    }

    /** Release the process-scoped native model/service; next use will lazily reload it. */
    fun releaseBergamot() {
        synchronized(this) {
            bergamotDelegate?.release()
            bergamotDelegate = null
        }
    }

    /** Experimental Mozilla Bergamot EN→AR; never falls back silently when explicitly selected. */
    private fun createBergamot(context: Context): Translator {
        val style = styleOf(context)
        if (!BergamotEnArModel.isInstalled(context)) {
            return object : Translator {
                override val engineId = "bergamot_en_ar"
                override fun translatePage(blocks: List<TranslationBlock>) = PageTranslation.Failure(
                    FailureReason.MODEL_DOWNLOAD_FAILED,
                    "Bergamot model is not downloaded. Open Settings → Offline translation and download it first.",
                    engineId
                )
            }
        }
        val session = names(context)
        val delegate = bergamotDelegate ?: synchronized(this) {
            bergamotDelegate ?: BergamotPageTranslator(context).also { bergamotDelegate = it }
        }
        val bergamot = CachingTranslator(
            delegate,
            sharedCache, disk(context), style,
            memo = memo("bergamot_en_ar", style),
            pinnedOverride = session::rewrite,
            onTranslationPair = pairRecorder(context)
        )
        // Keep Bergamot as the primary engine, but recover bubbles withheld by the shared
        // Arabic quality guard with the already-installed offline ML Kit engine. The fallback
        // receives only blank/rejected bubbles; accepted Bergamot output is never retranslated.
        return QualityFallbackTranslator(
            primary = bergamot,
            fallback = createOffline(context)
        ) { requested, elapsedMs, filled ->
            com.manhwalens.app.util.PipelineTracer.span(
                "Bergamot -> ML Kit fallback",
                elapsedMs,
                "requested=$requested filled=$filled"
            )
        }
    }

    /** ML Kit only (progressive pass): same shared caches, ignores the engine pref. */
    fun createOffline(
        context: Context,
        onRawTranslation: ((MlKitTranslationTrace) -> Unit)? = null
    ): Translator {
        ensureBatchPolicy(context)
        TranslationPrefs(context).refreshGlossary()
        val style = styleOf(context)
        val session = names(context)
        return CachingTranslator(
            MlKitTranslator(
                nameTableProvider = { texts -> session.tableFor(texts).table },
                onRawTranslation = onRawTranslation
            ),
            sharedCache, disk(context), style, memo = memo("ml_kit_en_ar", style),
            pinnedOverride = session::rewrite,
            onTranslationPair = pairRecorder(context)
        )
    }

    /**
     * Progressive display variant: each block is pushed to [onBlockReady]
     * as it completes (per-block ≤3 in flight inside ML Kit), while cache hits
     * are emitted instantly. Used by the tap pipeline.
     */
    fun createProgressive(
        context: Context,
        onBlockReady: (TranslatedBlock) -> Unit,
        onRawTranslation: ((MlKitTranslationTrace) -> Unit)? = null
    ): Translator {
        ensureBatchPolicy(context)
        TranslationPrefs(context).refreshGlossary()
        val style = styleOf(context)
        val session = names(context)
        // The delegate emits raw ML Kit output as soon as each task completes. Run that
        // early callback through the exact same output sanitation, series-name correction,
        // and Arabic guard used by CachingTranslator's final page result. Cache hits use the
        // same callback, so early and final patches cannot disagree about visible text.
        val deliverProgressive: (TranslatedBlock) -> Unit = { raw ->
            TranslationOutputSanitizer.prepare(raw, session::rewrite)?.let { safe ->
                runCatching { onBlockReady(safe) }
            }
        }
        return CachingTranslator(
            MlKitTranslator(
                deliverProgressive,
                nameTableProvider = { texts -> session.tableFor(texts).table },
                onRawTranslation = onRawTranslation
            ),
            sharedCache,
            disk(context),
            style,
            onBlockReady = deliverProgressive,
            memo = memo("ml_kit_en_ar", style),
            pinnedOverride = session::rewrite,
            onTranslationPair = pairRecorder(context)
        )
    }

    /**
     * Whether a selected remote engine can run as a best-effort upgrade beside the
     * fast on-device draft. An invalid/missing endpoint never blocks the ML Kit path.
     */
    fun remoteUpgradeAvailable(context: Context): Boolean {
        val prefs = TranslationPrefs(context)
        if (!OfflineTranslationPolicy.canUseRemoteUpgrade(prefs.offlineMode, prefs.engine)) return false
        return prefs.engine == TranslationEngine.GEMINI && prefs.hasGeminiKey && !prefs.geminiCircuit.isOpen()
    }

    /**
     * Build ONLY the configured remote engine, or return null when unavailable.
     * It never silently substitutes ML Kit; callers can keep the already-painted draft.
     */
    fun createRemoteOnly(context: Context): Translator? {
        ensureBatchPolicy(context)
        val prefs = TranslationPrefs(context)
        if (!OfflineTranslationPolicy.canUseRemoteUpgrade(prefs.offlineMode, prefs.engine)) return null
        prefs.refreshGlossary()
        return if (prefs.engine == TranslationEngine.GEMINI && prefs.hasGeminiKey && !prefs.geminiCircuit.isOpen()) gemini(context, prefs) else null
    }

    /** Backwards-compatible Gemini-only factory entry point. */
    fun createGeminiOnly(context: Context): Translator? {
        val prefs = TranslationPrefs(context)
        if (!OfflineTranslationPolicy.canCreateGeminiOnly(prefs.offlineMode, prefs.engine, prefs.hasGeminiKey, prefs.geminiCircuit.isOpen())) return null
        prefs.refreshGlossary()
        return gemini(context, prefs)
    }

    /** Count both caches, initializing the disk cache so persisted entries are included. */
    fun cacheSize(context: Context): Int = sharedCache.size() + disk(context).size()

    /** Clear every translation-result cache, including the persisted file even after a cold start. */
    fun clearCache(context: Context) {
        sharedCache.clear()
        disk(context).clear()
        CachingTranslator.resetMemos()
    }
}
