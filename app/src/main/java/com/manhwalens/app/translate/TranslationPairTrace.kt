package com.manhwalens.app.translate

/** Per-bubble diagnostic: candidate is the pre-processing engine/cache value; final is the accepted value returned for display (blank when rejected). */
data class TranslationPairTrace(
    val blockId: Int,
    val sourceEnglish: String,
    val finalArabic: String,
    val candidateArabic: String,
    val engineId: String,
    val status: String,
    val detail: String,
    val elapsedMs: Long,
    /** Unique within the running process for one translatePage request. */
    val requestId: String = ""
)
