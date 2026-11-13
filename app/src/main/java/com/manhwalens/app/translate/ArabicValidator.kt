package com.manhwalens.app.translate

/**
 * Output guard for every engine (v0.11.0).
 *
 * v0.10.0 asked one question: "is this Arabic, or an English echo?" That let through the
 * three failure modes visible on real pages:
 *  1. **Half-translated bubbles** — "أنت ستستمر في الوصول من الآن. You're going to keep
 *     coming from now on." The old guard only rejected a *complete* source echo.
 *  2. **Untranslated short lines** — "......", "???" and "!!" came back unchanged and were
 *     painted over the art.
 *  3. **Model noise** — a reply with a leading "الترجمة:" label, a stray bracket or a
 *     transliteration of the word "Translation".
 *
 * The guard therefore returns a typed [Issue] (still null when the text is usable) and a
 * separate [clean] pass that strips harmless noise BEFORE judging. Everything stays pure
 * Kotlin so the rules are unit-tested on the JVM.
 */
object ArabicValidator {

    enum class Issue {
        EMPTY_OUTPUT,
        NO_ARABIC_SCRIPT,
        UNTRANSLATED_ECHO,
        MALFORMED_OUTPUT,
        /** Arabic script mixed with a long, untranslated English sentence. */
        PARTIAL_TRANSLATION,
        /** A reply that is only punctuation / only a label: nothing to paint. */
        MEANINGLESS_OUTPUT
    }

    private val arabicLetter =
        Regex("[\\u0621-\\u063A\\u0641-\\u064A\\u066E-\\u06D3\\u06FA-\\u06FC\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF]")

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

    /** Latin tokens that are fine inside Arabic dialogue: names, units, SFX, acronyms. */
    private val benignLatin = Regex("^([A-Z][a-z'’\\-]{1,20}|[A-Z]{2,5}|\\d+[a-zA-Z%]?)$")

    /** Labels models prepend even when told not to. */
    private val LEADING_LABEL = Regex(
        "^(?:الترجمة|الترجمه|ترجمة|النص|الإجابة|الجواب|الرد|بالعربية|بالعربي|arabic|translation|output)\\s*[:：]\\s*",
        RegexOption.IGNORE_CASE
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
        if (isOnlyPunctuation(output)) return Issue.MEANINGLESS_OUTPUT

        val hasArabic = arabicLetter.containsMatchIn(output)
        if (hasArabic) {
            if (containsEnglishEcho(source, output)) return Issue.UNTRANSLATED_ECHO
            if (hasLongEnglishTail(source, output)) return Issue.PARTIAL_TRANSLATION
            if (isOnlyLabel(output)) return Issue.MEANINGLESS_OUTPUT
            return null // Arabic dialogue may naturally retain a Latin character or name.
        }

        val sourceNorm = normalize(source)
        val outputNorm = normalize(output)
        // "……" in, "……" out is not a translation failure — it is nothing to translate.
        if (sourceNorm.isEmpty() && outputNorm.isEmpty()) return Issue.MEANINGLESS_OUTPUT
        val isExactEcho = sourceNorm.isNotEmpty() && sourceNorm == outputNorm
        if (isExactEcho && !isLikelyStandaloneName(source)) return Issue.UNTRANSLATED_ECHO

        // Some translators correctly preserve a single unfamiliar character/place name.
        // Do not extend this exception to common words such as "Yes", "What", or "Stop".
        if (isLikelyStandaloneName(source) && isExactEcho) return null

        return Issue.NO_ARABIC_SCRIPT
    }

    fun hasArabic(text: String): Boolean = arabicLetter.containsMatchIn(text)

    /**
     * Strip output noise before it reaches the cache or the screen: leading "الترجمة:"
     * labels, wrapping quotes/brackets a model added, the "(translated from English)"
     * note, and stray zero-width characters. Never changes the meaning.
     */
    fun clean(translated: String): String {
        var out = translated
            .replace("\u200B", "").replace("\u200E", "").replace("\u200F", "")
            .replace("\uFEFF", "")
            .trim()
        out = LEADING_LABEL.replace(out, "")
        out = out.trim().trim('"', '\'', '`', '«', '»').trim()
        // A trailing note like "(Translated from English)" carries no dialogue.
        out = Regex("\\(\\s*(?:translated|translation)[^)]*\\)\\s*$", RegexOption.IGNORE_CASE)
            .replace(out, "")
        return out.trim()
    }

    private fun isOnlyPunctuation(text: String): Boolean =
        text.none { it.isLetterOrDigit() }

    /** "الترجمة:" alone after [clean] (or a lone ⟨…⟩ marker) says nothing. */
    private fun isOnlyLabel(text: String): Boolean {
        val stripped = arabicLetter.replace(text, "").filter { it.isLetter() }
        return stripped.isEmpty() && !text.any { it.isDigit() } && text.length <= 3
    }

    private val englishToken = Regex("[A-Za-z]+(?:['’][A-Za-z]+)*")

    /**
     * Catch a complete English source echo embedded in otherwise Arabic text without
     * rejecting unrelated Latin abbreviations or transliterated names.
     */
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

    /**
     * PARTIAL translation: Arabic plus a run of ≥3 consecutive untranslated English words
     * that also appear in the source. Capitalised tokens (names) break a run, so a preserved
     * name such as "Wei Wuxian" never trips this, while an English sentence left in the
     * middle of an Arabic bubble does.
     */
    private fun hasLongEnglishTail(source: String, output: String): Boolean {
        val sourceTokens = englishToken.findAll(source).map { it.value.lowercase() }.toSet()
        if (sourceTokens.size < 3) return false
        val latin = englishToken.findAll(output).map { it.value }.toList()
        if (latin.size < 3) return false

        var run = 0
        var best = 0
        for ((index, token) in latin.withIndex()) {
            val lower = token.lowercase()
            val inSource = lower in sourceTokens
            val sentenceStart = token.first().isUpperCase() && token.drop(1).any { it.isLowerCase() }
            val countsAsEcho = inSource && (!sentenceStart || run == 0)
            if (countsAsEcho) {
                run++
                if (run > best) best = run
            } else {
                run = 0
            }
            if (index == latin.size - 1) run = 0
        }
        return best >= 3
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
