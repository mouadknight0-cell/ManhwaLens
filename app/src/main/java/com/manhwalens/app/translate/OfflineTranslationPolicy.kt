package com.manhwalens.app.translate

/**
 * Central guard for every optional cloud translation entry point.
 *
 * Offline mode is a privacy boundary, not merely an engine preference:
 * when enabled, local ML Kit is mandatory and no remote upgrade may be created.
 */
internal object OfflineTranslationPolicy {
    fun mustUseLocal(offlineMode: Boolean): Boolean = offlineMode

    fun canUseRemoteUpgrade(
        offlineMode: Boolean,
        engine: TranslationEngine
    ): Boolean = !offlineMode && engine != TranslationEngine.ML_KIT

    fun canCreateGeminiOnly(
        offlineMode: Boolean,
        engine: TranslationEngine,
        hasGeminiKey: Boolean,
        circuitOpen: Boolean
    ): Boolean = !offlineMode &&
        engine == TranslationEngine.GEMINI &&
        hasGeminiKey &&
        !circuitOpen
}
