package com.manhwalens.app.translate

/** Final per-bubble pair after shared cleanup and quality validation. */
data class TranslationPairTrace(
    val blockId: Int,
    val sourceEnglish: String,
    val finalArabic: String,
    val candidateArabic: String,
    val engineId: String,
    val status: String,
    val detail: String,
    val elapsedMs: Long
)
