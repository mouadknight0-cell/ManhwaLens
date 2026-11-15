package com.manhwalens.app.translate

/**
 * One deterministic output-sanitizing path shared by final page assembly and progressive
 * painting. Keeping this pure makes it possible to test the exact text that reaches the overlay.
 */
object TranslationOutputSanitizer {

    /** Remove harmless model noise and normalize Arabic dialogue typography. */
    fun clean(text: String): String {
        if (text.isBlank()) return text
        return BubbleTextRules.tidyDialogue(
            BubbleTextRules.polishArabic(ArabicValidator.clean(text))
        )
    }

    /**
     * Build a paintable result after sanitation and optional authoritative name repair.
     * Returns null for blank, echoed, malformed, or otherwise rejected output.
     */
    fun prepare(
        block: TranslatedBlock,
        pinnedOverride: ((String, String) -> String)? = null
    ): TranslatedBlock? {
        val cleaned = clean(block.arabic)
        if (cleaned.isBlank()) return null
        val dialogueRepaired = ShortDialogueOverrides.apply(block.english, cleaned)
        val corrected = if (pinnedOverride != null) {
            runCatching { pinnedOverride(block.english, dialogueRepaired) }.getOrDefault(dialogueRepaired)
        } else {
            dialogueRepaired
        }
        if (corrected.isBlank() || ArabicValidator.issue(block.english, corrected) != null) return null
        return block.copy(arabic = corrected)
    }
}
