package com.manhwalens.app.translate

import java.text.Normalizer
import java.util.Locale

/**
 * Conservative source-aware repairs for a tiny set of unambiguous, frequent dialogue/SFX
 * failures observed in the ML Kit EN→AR device corpus. Exact source matching only: never
 * rewrite a longer sentence or guess from the translated output alone.
 */
object ShortDialogueOverrides {
    private val exact = mapOf(
        "NO" to "لا.",
        "RUN" to "اهرب!",
        "KRAK" to "طَق!"
    )

    fun apply(source: String, translated: String): String {
        if (translated.isBlank()) return translated
        val key = Normalizer.normalize(
            BubbleTextRules.oneLine(BubbleTextRules.normalize(Glossary.prepare(source))),
            Normalizer.Form.NFKC
        ).uppercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
        return exact[key] ?: translated
    }
}
