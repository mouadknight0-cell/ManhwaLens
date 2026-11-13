package com.manhwalens.app.ocr

/**
 * Removes text that carries no meaning for a manhwa/manhwa reader (v0.11.0).
 *
 * Reader reports that drove this file:
 *  - watermarks and scanlator handles ("tapas", "MANGADEX", "read at …", "discord.gg/…",
 *    "TR/EN/AR", "translator: name") were translated as if they were dialogue;
 *  - ellipsis-only bubbles ("……", "…!!", "...") got an Arabic patch that covered the
 *    dots and said nothing;
 *  - the app's own overlay caption ("Scanning / Same page"), the chapter header
 *    ("Chapter 23") and page counters leaked into the translated blocks.
 *
 * The rules stay deliberately narrow so real dialogue always survives:
 * a block is dropped only when it is *provably* meaningless — no letters at all
 * (ellipsis, dashes, arrows), a pure SFX burst, an OCR fragment of our own UI, a known
 * watermark/credit line, or a bare chapter/episode/page number.
 *
 * Pure Kotlin (JVM-tested).
 */
object MeaninglessText {

    /** Why a block was dropped (metrics use the name; the debug panel shows it verbatim). */
    enum class Reason {
        /** No letter in any script: "……", "...", "—", "!?", "♪". */
        PUNCTUATION_ONLY,
        /** Latin letters only used as decoration: "x2", "#24", "①". */
        DIGITS_OR_SYMBOLS_ONLY,
        /** Known onomatopoeia burst ("KRAK!", "THUD THUD"). */
        SOUND_EFFECT,
        /** Watermark, scanlator credit, site URL, social handle. */
        WATERMARK,
        /** Our own overlay/UI wording ("Scanning", "Same page", "Live"). */
        APP_CHROME,
        /** "Chapter 23", "Ep 12", "Page 5" — series furniture, not dialogue. */
        CHAPTER_HEADER,
        /** OCR saw burned-in Arabic (already our own patch) — nothing to translate. */
        ALREADY_ARABIC,
        /** A single Latin letter/word fragment the recognizer produced from art. */
        OCR_FRAGMENT
    }

    data class Verdict(val keep: Boolean, val reason: Reason) {
        companion object {
            val KEEP = Verdict(true, Reason.PUNCTUATION_ONLY)
        }
    }

    // ---------------------------------------------------------------- data

    /** Latin letters used as "art", never as words. */
    private val DECORATIVE = Regex("^[xXvVı|/\\\\\\-*#@&+~_=<>\\[\\]{}()0-9\\s]+$")

    private val ARABIC = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF]")
    private val LATIN = Regex("[A-Za-z]")
    private val ANY_LETTER = Regex("\\p{L}")

    /** Sight words: if a block contains one of these it is dialogue, full stop. */
    private val DIALOGUE_ANCHORS = setOf(
        "i", "you", "he", "she", "we", "they", "it", "me", "my", "your", "his", "her",
        "the", "a", "an", "is", "are", "was", "were", "be", "am", "do", "does", "did",
        "not", "no", "yes", "and", "but", "or", "if", "so", "to", "of", "in", "on", "at",
        "for", "with", "from", "that", "this", "what", "who", "how", "why", "when", "where",
        "will", "would", "can", "could", "should", "have", "has", "had", "let", "go", "get",
        "come", "wait", "stop", "please", "sorry", "thank", "thanks", "hello", "hey", "okay",
        "ok", "really", "just", "now", "here", "there", "again", "still", "too", "very",
        "want", "need", "know", "think", "said", "say", "tell", "look", "see", "take"
    )

    /** Watermark / credit vocabulary (lower-cased, substring match on short blocks). */
    private val WATERMARK_TOKENS = listOf(
        // NB: no bare two-letter marks here — "io", "me", "ok", "no", "go" are real dialogue.
        "www.", "http:", "https:", ".com", ".net", ".org", ".xyz", ".to/", "://",
        "tapas", "mangadex", "manga dex", "webtoon.com", "webtoons.com", "discord",
        "patreon", "ko-fi", "kofi", "buymeacoffee", "t.me/", "instagram", "twitter",
        "facebook", "tiktok", "reddit", "read at", "read on", "read more",
        "scanlation", "scanlator", "translator:", "translated by", "typeset", "proofread",
        "cleaned by", "credit", "credits", "do not reupload", "don't reupload", "reupload",
        "official translation", "fan translation", "all rights reserved", "copyright",
        "©", "®", "™", "raws by", "raw provided", "support the author", "buy the official"
    )

    /**
     * Scanlator language tags often left in a corner: "TR", "AR/EN", "EN-CN".
     * Deliberately NOT any two capitals: "OK", "NO", "GO" and "HI" are dialogue. A tag is
     * recognised only when it repeats the same pair ("EN EN"), joins pairs with a separator
     * ("AR/EN"), or is one of the codes scanlators actually stamp on the page.
     */
    private val LANG_TAG = Regex("^([A-Z]{2})(\\s*[/|·-]\\s*[A-Z]{2})+$")
    private val KNOWN_LANG_TAGS = setOf(
        "EN", "AR", "TR", "ES", "FR", "PT", "ID", "TH", "VI", "RU", "CN", "KR", "JP",
        "ZH", "MS", "PH", "DE", "IT", "PL", "MX", "BR", "US", "UK"
    )

    /** Onomatopoeia cores (also used by [BlockFilter]); compared upper-cased. */
    private val SFX = setOf(
        // English comics
        "KRAK", "KRAAK", "BOOM", "BAM", "POW", "WHAM", "THUD", "CRASH", "SPLASH", "BANG",
        "KAPOW", "SNIKT", "THWIP", "STOMP", "SMASH", "WHUMP", "DOOM", "KLANG", "GRRR",
        "ROAR", "GROWL", "SLASH", "SLAM", "KABOOM", "CLASH", "THWACK", "CREAK", "SIGH",
        "GASP", "SOB", "HAHA", "HEHE", "HOHO", "GIGGLE", "SIGH", "PUFF", "HISS", "BUZZ",
        "CLICK", "CLACK", "DRIP", "SPLAT", "WHOOSH", "SWOOSH", "RUMBLE", "SCREECH", "BEEP",
        // webtoon staples
        "TAP", "STEP", "KNOCK", "RING", "DING", "SLURP", "MUNCH", "CHOMP", "GULP", "SIP",
        "SNAP", "CRACK", "POP", "FLAP", "FLICK", "SWISH", "TWANG", "ZAP", "FWOOSH"
    )

    /** Titles/numbering that is series furniture rather than dialogue. */
    private val CHAPTER = Regex(
        "^(chapter|chap\\.?|ch\\.?|episode|ep\\.?|ep|season|vol\\.?|volume|page|pg\\.?|part)\\s*[#:]?\\s*\\d+([\\s\\-–~]*(of|/)\\s*\\d+)?\\s*$",
        RegexOption.IGNORE_CASE
    )

    // ---------------------------------------------------------------- rules

    /**
     * @param text      the block's text (newlines allowed)
     * @param ownStrings UI strings that belong to this app (lower-cased)
     */
    fun classify(text: String, ownStrings: Set<String> = emptySet()): Verdict {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.isEmpty()) return Verdict(false, Reason.OCR_FRAGMENT)

        // 1) Our own UI wording, before anything else ("Same page", "Scanning…").
        val lower = flat.lowercase().trim('.', '…', '!', '?', ':', ' ')
        if (lower in ownStrings) return Verdict(false, Reason.APP_CHROME)

        // 2) Arabic already on the page: our patch burned in, or a translated release.
        if (ARABIC.containsMatchIn(flat)) return Verdict(false, Reason.ALREADY_ARABIC)

        val letters = flat.count { it.isLetter() }
        val hasLatin = LATIN.containsMatchIn(flat)

        // 3) No letters at all: "……", "...", "—!!", a lone emoji.
        if (letters == 0) {
            return Verdict(false, if (ANY_LETTER.containsMatchIn(flat)) Reason.OCR_FRAGMENT else Reason.PUNCTUATION_ONLY)
        }

        // 4) Decoration + digits only ("x2", "#24", "① ②").
        if (!hasLatin && DECORATIVE.matches(flat)) return Verdict(false, Reason.DIGITS_OR_SYMBOLS_ONLY)
        if (hasLatin && DECORATIVE.matches(flat)) return Verdict(false, Reason.DIGITS_OR_SYMBOLS_ONLY)

        // 5) Watermarks / credits / handles. Two-character marks (©, ®, ™, ".io") only
        //    count inside a longer block; a two-letter mark must never eat "OK" or "NO".
        val watermarkHit = WATERMARK_TOKENS.firstOrNull { tok ->
            if (tok.length <= 2) {
                !tok.all { it.isLetter() } && flat.contains(tok) && flat.length <= 24
            } else {
                lower.contains(tok)
            }
        }
        if (watermarkHit != null) return Verdict(false, Reason.WATERMARK)
        if (LANG_TAG.matches(flat)) return Verdict(false, Reason.WATERMARK)
        if (flat.length <= 6 && flat.uppercase() in KNOWN_LANG_TAGS) return Verdict(false, Reason.WATERMARK)

        // 6) "Chapter 23", "Ep. 12/40", "Page 7".
        if (CHAPTER.matches(flat)) return Verdict(false, Reason.CHAPTER_HEADER)

        val tokens = flat.split(' ').filter { it.isNotBlank() }
        val cores = tokens.map { it.uppercase().filter { ch -> ch.isLetter() } }.filter { it.isNotBlank() }

        // 7) Pure SFX: every token is a known burst, or one repeated burst ("THUD THUD THUD").
        if (cores.isNotEmpty() && cores.all { it in SFX }) return Verdict(false, Reason.SOUND_EFFECT)
        if (cores.size >= 2 && cores.distinct().size == 1 && cores.first().length >= 3 &&
            looksLikeBurst(cores.first())
        ) {
            return Verdict(false, Reason.SOUND_EFFECT)
        }

        // 7b) One bare token that is an extended/vowel-less burst ("GRRRR", "SHHHH", "PSST",
        //     "HM") — SFX, not dialogue. A real one-word bubble has a vowel ("OK", "NO").
        if (cores.size == 1) {
            val core = cores.first()
            val noVowel = core.none { it in "AEIOUY" }
            val tripled = Regex("(.)\\1\\1").containsMatchIn(core)
            if (noVowel || tripled) return Verdict(false, Reason.SOUND_EFFECT)
        }

        // 8) One or two vowel-less fragments that are not real words: OCR debris ("N G",
        //    "R T"). Real two-letter dialogue ("OK", "NO", "GO", "HI") contains a sight word
        //    or a vowel and is kept.
        if (tokens.size <= 2 && tokens.all { t ->
                t.none { it.isLetter() } ||
                    (t.length <= 2 && t.lowercase() !in DIALOGUE_ANCHORS && t.none { c -> c.lowercaseChar() in "aeiouy" })
            }
        ) {
            return Verdict(false, Reason.OCR_FRAGMENT)
        }
        if (tokens.size == 1) {
            val core = cores.first()
            val isWord = core.length >= 2 && core.count { it in "AEIOU" } >= 1
            val isKnown = lower in DIALOGUE_ANCHORS
            if (!isWord && !isKnown && core.length <= 4) return Verdict(false, Reason.OCR_FRAGMENT)
        }

        return Verdict(true, Reason.PUNCTUATION_ONLY)
    }

    /** True when one bare token is a repeated-burst shape: "THUD", "GRRRR", "BZZZ". */
    private fun looksLikeBurst(core: String): Boolean {
        if (core.length < 3 || core.length > 12) return false
        // 3+ identical letters in a row is an extended SFX ("GRRRR", "BZZZ", "SHHHH") —
        // English words never triple a letter.
        if (Regex("(.)\\1\\1").containsMatchIn(core)) return true
        // Guttural bursts with no vowel at all ("GRR", "KRR", "SHH") are SFX too; a real
        // word always carries a vowel.
        return core.none { it in "AEIOUY" } && core.length <= 6
    }

    /** Convenience: keep-set filter for a list of block texts (used by the pipeline). */
    fun filter(texts: List<String>, ownStrings: Set<String> = emptySet()): Pair<List<Int>, List<Pair<Int, Reason>>> {
        val kept = ArrayList<Int>(texts.size)
        val dropped = ArrayList<Pair<Int, Reason>>()
        texts.forEachIndexed { i, t ->
            val v = classify(t, ownStrings)
            if (v.keep) kept += i else dropped += i to v.reason
        }
        return kept to dropped
    }

    /** Dialogue-anchor check for other filters that need a "this is real speech" signal. */
    fun hasDialogueAnchor(text: String): Boolean {
        val tokens = text.lowercase().split(Regex("[^a-z']+")).filter { it.isNotBlank() }
        return tokens.any { it in DIALOGUE_ANCHORS }
    }
}
