package com.manhwalens.app.translate

/** One block to translate (typically a merged bubble, keyed by its id). */
data class TranslationBlock(
    val id: Int,
    val english: String
)

/** One translated block. */
data class TranslatedBlock(
    val id: Int,
    val english: String,
    val arabic: String
)

/** Outcome of translating one page (all blocks in a single logical batch). */
sealed interface PageTranslation {
    data class Success(
        val blocks: List<TranslatedBlock>,
        val engineId: String,
        val elapsedMs: Long,
        val cacheHits: Int,
        val cacheMisses: Int
    ) : PageTranslation

    data class Failure(
        val reason: FailureReason,
        val message: String,
        val engineId: String
    ) : PageTranslation
}

/** Machine-readable failure causes for UI + tests. */
enum class FailureReason {
    NO_INTERNET,
    INVALID_API_KEY,
    TIMEOUT,
    RATE_LIMITED,
    MODEL_NOT_FOUND,
    MODEL_DOWNLOAD_FAILED,
    PARSE_ERROR,
    EMPTY_INPUT,
    UNKNOWN
}

/** User-facing engine choice. */
enum class TranslationEngine {
    /** ML Kit EN→AR on the device; offline after one model download. */
    ML_KIT,

    /** Google Gemini API; better colloquial quality; needs an API key. */
    GEMINI,

    /** Self-hosted LibreTranslate/Argos server; one batched request per page. */
    LIBRE_TRANSLATE
}
