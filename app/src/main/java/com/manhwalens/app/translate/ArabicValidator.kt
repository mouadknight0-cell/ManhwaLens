package com.manhwalens.app.translate

/**
 * Conservative guard for cloud/self-hosted translation responses.
 *
 * This is a mechanical safety check, not a semantic quality score: it catches empty replies,
 * malformed OCR-like garbage, and whole English echoes before they are cached or replace a
 * readable on-device draft. A one-word proper name may legitimately remain Latin, so that
 * narrow case is allowed unless the token is a common English dialogue word.
 */
object ArabicValidator {

    enum class Issue {
        EMPTY_OUTPUT,
        NO_ARABIC_SCRIPT,
        UNTRANSLATED_ECHO,
        MALFORMED_OUTPUT
    }

    private val arabicLetter = Regex("[\\u0621-\\u063A\\u0641-\\u064A\\u066E-\\u06D3\\u06FA-\\u06FC\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF]")

    /** Common short dialogue words that must not be mistaken for preserved character names. */
    private val allowedControlCharacters = setOf('\n', '\r', '\t')

    private val commonEnglishWords = setOf(
        "a", "again", "all", "always", "am", "an", "and", "are", "as", "at", "back", "bad",
        "be", "been", "better", "big", "but", "bye", "can", "come", "dead", "did", "do", "does",
        "done", "enough", "fine", "get", "go", "good", "goodbye", "great", "hello", "help", "hey",
        "hi", "how", "i", "is", "it", "know", "let", "like", "look", "love", "me", "maybe", "more",
        "much", "my", "never", "no", "not", "now", "nothing", "oh", "okay", "ok", "please", "really",
        "ready", "right", "run", "still", "sure", "sorry", "stop", "thanks", "thank", "that", "the",
        "there", "they", "this", "to", "too", "up", "wait", "we", "well", "what", "when", "where",
        "who", "why", "will", "with", "yes", "you", "your"
    )

    /** Return null when the output is safe to use, otherwise a machine-readable reason. */
    fun issue(source: String, translated: String): Issue? {
        // Check before trim: trim() would otherwise erase malformed control bytes at the edges.
        if (translated.any { ch ->
                ch == '\uFFFD' || (Character.isISOControl(ch) && ch !in allowedControlCharacters)
            }
        ) return Issue.MALFORMED_OUTPUT

        val output = translated.trim()
        if (output.isEmpty()) return Issue.EMPTY_OUTPUT
        val hasArabic = arabicLetter.containsMatchIn(output)
        if (hasArabic) {
            if (containsEnglishEcho(source, output)) return Issue.UNTRANSLATED_ECHO
            return null // Arabic dialogue may naturally retain a Latin character or name.
        }

        val sourceNorm = normalize(source)
        val outputNorm = normalize(output)
        val isExactEcho = sourceNorm.isNotEmpty() && sourceNorm == outputNorm
        if (isExactEcho && !isLikelyStandaloneName(source)) return Issue.UNTRANSLATED_ECHO

        // Some translators correctly preserve a single unfamiliar character/place name.
        // Do not extend this exception to common words such as "Yes", "What", or "Stop".
        if (isLikelyStandaloneName(source) && isExactEcho) return null

        return Issue.NO_ARABIC_SCRIPT
    }

    fun hasArabic(text: String): Boolean = arabicLetter.containsMatchIn(text)

    private val englishToken = Regex("[A-Za-z]+(?:['’][A-Za-z]+)*")

    /** Catch a complete English source echo embedded in otherwise Arabic text without
     * rejecting unrelated Latin abbreviations or transliterated names. */
    private fun containsEnglishEcho(source: String, output: String): Boolean {
        val sourceTokens = englishToken.findAll(source).map { it.value.lowercase() }.toList()
        if (sourceTokens.isEmpty()) return false
        val outputTokens = englishToken.findAll(output).map { it.value.lowercase() }.toList()
        if (sourceTokens.size == 1) {
            return sourceTokens.single() in outputTokens && !isLikelyStandaloneName(source)
        }
        if (isLikelyNamePhrase(source, sourceTokens)) return false
        return outputTokens.windowed(sourceTokens.size).any { it == sourceTokens }
    }

    private fun isLikelyNamePhrase(source: String, tokens: List<String>): Boolean {
        if (tokens.size !in 2..4) return false
        val originalTokens = englishToken.findAll(source).map { it.value }.toList()
        if (originalTokens.size != tokens.size) return false
        if (originalTokens.any { !it.first().isUpperCase() }) return false
        return tokens.none { it in commonEnglishWords }
    }

    private fun normalize(text: String): String =
        text.lowercase().filter { it.isLetterOrDigit() }

    private fun isLikelyStandaloneName(source: String): Boolean {
        val token = source.trim().trimEnd('.', '!', '?', '…', '。', '！', '？', ',', ':', ';')
        if (token.length !in 2..32 || token.any(Char::isWhitespace)) return false
        if (!token.first().isUpperCase()) return false
        if (!token.all { it.isLetter() || it == '-' || it == '\'' || it == '’' }) return false
        if (!token.any { it.isLowerCase() }) return false
        return token.lowercase() !in commonEnglishWords
    }
}
