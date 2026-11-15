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
        "KRAK" to "طَق!",
        "LOOKS LIKE IT S DONE" to "يبدو أن الأمر انتهى!",
        "HA THIS IS A PIECE OF CAKE" to "ها، هذا سهل للغاية.",
        "THIS IS WHAT YOU GET AFTER KILLING A MONSTER THE MAGICAL CORE" to "هذا ما تحصل عليه بعد قتل الوحش: النواة السحرية.",
        "THE MAGICAL CORE" to "النواة السحرية."
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
