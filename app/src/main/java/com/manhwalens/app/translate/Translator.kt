package com.manhwalens.app.translate

/**
 * One logical batch = one page. Implementations may do a single request with
 * the whole page as context (preferred) or per-block calls with an internal
 * fallback; the contract is identical either way.
 *
 * Blocking API by design: callers run this on a background executor (never on
 * the main thread). Must never throw — outcomes are reported via
 * [PageTranslation]. Implementations must never log secrets (API keys).
 */
interface Translator {

    /** Stable engine identifier; also the cache namespace. */
    val engineId: String

    fun translatePage(blocks: List<TranslationBlock>): PageTranslation
}
