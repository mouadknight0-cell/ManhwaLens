package com.manhwalens.app.translate

/**
 * Scene-level name resolution (v0.11.0).
 *
 * WHY: on-device ML Kit has no idea that "Yiren" is a person, so one page spelled it
 * "ييرين", the next "يرين" and a third left it Latin. Even Gemini drifts between
 * "هان جي وو" and "هانجيو" across chapters. Fixing one name at a time in the glossary
 * does not scale — a reader meets dozens of names per chapter.
 *
 * [NameMemory] does what a human scanlator does:
 *  1. **Detect** the proper nouns of a page with casing + position rules
 *     ([NameScanner]), so ordinary sentence-initial words ("Anyway", "You're") and
 *     ALL-CAPS sound effects are never mistaken for names.
 *  2. **Grade** each candidate: romanized Chinese / Japanese / Korean names look
 *     different from English words ([NameScript]), and a name that is repeated, owns a
 *     title ("Young Master <Name>"), or was chosen by the user is trusted more.
 *  3. **Pin** the page's names to ONE Arabic spelling — the user's own entry, else the
 *     built-in table, else a display name the model already produced on this page, else
 *     a deterministic transliteration — and provide the mapping to whatever engine runs.
 *     ML Kit gets the Arabic name inline (its only steering wheel); Gemini gets an
 *     ordered, short name sheet.
 *
 * Pure Kotlin (no Android deps) so the rules are unit-tested on the JVM.
 */
object NameMemory {

    /** One proper-noun candidate found on a page. */
    data class Mention(
        val text: String,
        /** Number of occurrences on the page. */
        val count: Int,
        /** Detected origin of the romanization. */
        val script: NameScript,
        /** True for honorifics/titles that must stay in the glossary as terms. */
        val isTerm: Boolean = false
    )

    /** A resolved page name: [text] as it appears in English, [arabic] the pinned spelling. */
    data class Pinned(
        val text: String,
        val arabic: String,
        val script: NameScript,
        /** How the Arabic form was decided — shown in the debug report. */
        val origin: Origin
    ) {
        enum class Origin { USER, BUILT_IN, PAGE_AI, TRANSLITERATION }
    }

    /**
     * Result of analysing one page: the pinned names plus what the engines should be told.
     * [inlineForMlKit] replaces the name inside the source text; [sheetForModel] is the
     * "keep these spellings" block for an instructable model.
     */
    data class Plan(
        val pinned: List<Pinned>,
        /** English form → pinned Arabic, longest first (ready for source substitution). */
        val table: Map<String, String>,
        /** Names whose Arabic the model produced on this page (bubble id → hints). */
        val notes: List<String> = emptyList()
    ) {
        val isEmpty: Boolean get() = pinned.isEmpty()
    }

    // ---------------------------------------------------------------- scanning

    /** Words that look like names but are English dialogue, titles, or SFX. */
    private val STOP = setOf(
        // sentence glue that starts a bubble
        "a", "about", "after", "again", "ah", "alright", "also", "and", "anyway", "are", "as",
        "at", "be", "because", "before", "but", "by", "can", "could", "do", "does", "don",
        "even", "every", "exactly", "for", "from", "get", "go", "good", "great", "had", "has",
        "have", "he", "her", "here", "hey", "hi", "his", "how", "i", "if", "in", "is", "it",
        "just", "keep", "let", "like", "look", "maybe", "me", "my", "no", "not", "now", "of",
        "oh", "okay", "ok", "on", "once", "one", "only", "or", "our", "out", "over", "please",
        "really", "right", "say", "see", "she", "should", "so", "some", "still", "sure",
        "take", "tell", "than", "thank", "thanks", "that", "the", "their", "then", "there",
        "these", "they", "thing", "think", "this", "those", "to", "too", "under", "until",
        "very", "wait", "want", "was", "we", "well", "were", "what", "when", "where", "which",
        "while", "who", "why", "will", "with", "would", "yeah", "yes", "yet", "you", "your",
        // comic furniture
        "chapter", "episode", "season", "page", "part", "vol", "volume",
        // honorifics/titles alone are not names
        "master", "miss", "mister", "mrs", "mr", "ms", "sir", "lady", "lord", "young",
        "senior", "junior", "big", "little", "old", "brother", "sister", "uncle", "aunt",
        "father", "mother", "mom", "dad", "boss", "captain", "general", "doctor", "doc",
        "teacher", "student", "president", "king", "queen", "prince", "princess",
        // capitalised mid-sentence connectives and fillers (webtoon dialogue uses these a lot)
        "except", "because", "although", "though", "unless", "however", "instead", "besides",
        "meanwhile", "otherwise", "suddenly", "finally", "actually", "honestly", "seriously",
        "anyway", "anyhow", "besides", "luckily", "hopefully", "obviously", "apparently",
        "yes", "yeah", "yep", "nope", "huh", "hmm", "umm", "uhh", "wow", "ouch", "thanks",
        "please", "sorry", "congrats", "congratulations", "morning", "evening", "tonight",
        "tomorrow", "yesterday", "today", "later", "soon", "anyone", "someone", "everyone",
        "nobody", "something", "nothing", "anything", "everything"
    )

    /** Every token in the text that carries an upper-case Latin letter, in page order. */
    private val latinWord = Regex("[A-Za-z][A-Za-z'’\\-]*")

    /**
     * Detect candidate names in the page's bubble texts (already OCR-cleaned, one entry
     * per bubble). Deterministic and cheap: one pass per bubble, a page of 30 bubbles is
     * well under a millisecond on a phone.
     */
    fun scan(texts: List<String>): List<Mention> {
        val counts = LinkedHashMap<String, Int>()
        val order = ArrayList<String>()
        for (raw in texts) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            val sentences = SENTENCE_SPLIT.split(text).filter { it.isNotBlank() }
            for (sentence in sentences) {
                // A mid-sentence capitalized word is a much stronger name signal than a
                // sentence-initial one: keep the position with the token.
                var first = true
                for (m in latinWord.findAll(sentence)) {
                    val token = m.value.trimEnd('\'', '\u2019')
                    val start = !first
                    first = false
                    if (!looksLikeName(token, start)) continue
                    val key = canonicalCase(token)
                    if (key !in counts) order.add(key)
                    counts[key] = (counts[key] ?: 0) + 1
                }
            }
        }
        return order.map { text ->
            Mention(
                text = text,
                count = counts.getValue(text),
                script = NameScript.of(text)
            )
        }
    }

    /**
     * Name-looking token? Uppercase first letter, at least one lowercase letter
     * (so "KRAK" / "!!" are out), 2..28 chars, not an English stop word, and — when it
     * starts a sentence — not a plain lowercase-with-capital English word that a
     * dictionary would know. Multi-word names ("Wei Wuxian") are joined by callers of
     * [scanPhrases]; the scanner itself returns single tokens.
     */
    fun looksLikeName(token: String, midSentence: Boolean): Boolean {
        if (token.length !in 2..28) return false
        if (!token.first().isUpperCase()) return false
        if (token.none { it.isLowerCase() }) return false // ALL CAPS = SFX / emphasis
        if (token.any { it.isDigit() }) return false
        val lower = token.lowercase()
        if (lower in STOP) return false
        // A capitalized ordinary word at the START of a bubble is usually just the first
        // word of a sentence ("Anyway, jogging…"). Only trust it when its romanization
        // looks Asian, when it is repeated, or when a title precedes it.
        if (midSentence) return true
        return NameScript.of(token) != NameScript.UNKNOWN || lower in KNOWN_SENTENCE_NAMES
    }

    /** Names that may legitimately open a sentence without other evidence. */
    private val KNOWN_SENTENCE_NAMES = setOf(
        "wei", "lan", "jiang", "jin", "nie", "wen", "hua", "xie", "mu", "pei", "ling", "jun",
        "luo", "shen", "liu", "shang", "ning", "qiu", "gongyi", "yue", "zhuzhi", "tianlang",
        "xiao", "xue", "zhao", "zheng", "zhou", "zhu", "zhang", "chen", "cao", "cui", "deng",
        "fang", "feng", "gao", "guan", "guo", "han", "hao", "hong", "huang", "jia", "kang",
        "kong", "li", "liang", "long", "meng", "pan", "peng", "qi", "qian", "qin", "qing",
        "ren", "rui", "song", "sun", "tan", "tang", "tao", "tian", "wang", "wu", "xiang",
        "xin", "xu", "xiong", "ye", "zhan", "zhi", "zhong", "jie", "hui", "banyue", "mobei"
    )

    /**
     * Canonical display form of a detected name: words capitalised, hyphens removed and the
     * pieces separated by a space — so "Ji-woo", "Jiwoo" and "JI WOO" all collapse to one
     * entry instead of three competing spellings.
     */
    fun canonicalName(raw: String): String =
        raw.replace('-', ' ').split(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ") { canonicalCase(it) }

    /** Canonical display form: first letter upper, rest as written ("WEI LIN" → "Wei Lin"). */
    fun canonicalCase(token: String): String = token.split(' ').joinToString(" ") { part ->
        if (part.isEmpty()) part else part[0].uppercaseChar() + part.drop(1)
    }

    private val SENTENCE_SPLIT = Regex("[.!?…。！？;\\n]+")

    // ---------------------------------------------------------------- phrases

    private val TITLES = listOf(
        "young master", "young lady", "young miss", "senior brother", "junior brother",
        "senior sister", "junior sister", "sect leader", "clan leader", "great master",
        "master", "miss", "mister", "mrs", "mr", "ms", "lady", "lord", "senior", "junior",
        "brother", "sister", "uncle", "aunt", "captain", "general", "doctor", "boss",
        "prince", "princess", "king", "queen", "saint", "saintess"
    )

    /**
     * Adjacent capitalized tokens that form one name ("Wei Wuxian", "Lan Wangji",
     * "Han Ji-woo"). Two tokens are joined when both look like names; a third token is
     * allowed only when the first two already look Asian (Chinese courtesy names are
     * three syllables).
     */
    fun phrases(text: String): List<String> {
        val words = latinWord.findAll(text).map { it.value.trimEnd('-', '\'', '’') }.toList()
        val out = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            val w = words[i]
            if (!looksLikeName(w, midSentence = true)) { i++; continue }
            val parts = arrayListOf(w)
            var j = i + 1
            while (j < words.size && parts.size < 3) {
                val next = words[j]
                if (!looksLikeName(next, midSentence = true)) break
                // "Wei Lin is here": "is" is a stop word and already rejected above.
                if (parts.size >= 2 && NameScript.of(next) == NameScript.UNKNOWN &&
                    NameScript.of(parts.joinToString(" ")) == NameScript.UNKNOWN
                ) break
                parts += next
                j++
            }
            val phrase = parts.joinToString(" ")
            out += phrase
            i = if (parts.size > 1) j else i + 1
        }
        return out
    }

    /** True when [phrase] starts with a title ("Young Master Wei"): the name is the tail. */
    fun stripTitle(phrase: String): Pair<String?, String> {
        val lower = phrase.lowercase()
        for (t in TITLES.sortedByDescending { it.length }) {
            if (lower == t) return t to ""
            if (lower.startsWith("$t ")) return t to phrase.substring(t.length + 1).trim()
        }
        return null to phrase
    }

    // ---------------------------------------------------------------- planning

    /**
     * Build the page plan.
     *
     * @param texts     every bubble's English text on the page (cleaned)
     * @param known     English → Arabic spellings already trusted (user + built-in)
     * @param pageArabic translations the engine already produced for this page, if any
     *                   (bubble id → Arabic). A name the model itself wrote consistently is
     *                   preferred over a fresh machine transliteration.
     */
    fun plan(
        texts: List<String>,
        known: Map<String, String> = Glossary.effective(),
        pageArabic: Map<Int, String> = emptyMap(),
        limit: Int = 24
    ): Plan {
        if (texts.isEmpty()) return Plan(emptyList(), emptyMap())

        val clean = texts.map { it.replace(NON_TEXT, " ").trim() }.filter { it.isNotBlank() }
        if (clean.isEmpty()) return Plan(emptyList(), emptyMap())

        val mentions = scan(clean)
        val mentionByText = mentions.associateBy { it.text.lowercase() }

        // Candidate phrases: multi-word first so "Wei Wuxian" wins over "Wei".
        // A hyphenated Korean name ("Ji-woo") and its spaced form ("Ji Woo") are the SAME
        // name; the hyphen is dropped so one spelling is pinned for both.
        val phrases = LinkedHashMap<String, Int>() // canonical phrase → occurrences
        for (t in clean) {
            for (p in phrases(t)) {
                val canonical = canonicalName(p)
                val (title, tail) = stripTitle(canonical)
                val target = if (tail.isNotBlank()) tail else canonical
                if (target.isBlank()) continue
                phrases[target] = (phrases[target] ?: 0) + 1
                if (title != null) phrases[canonical] = (phrases[canonical] ?: 0) + 1
            }
        }

        // Single tokens that appear on their own also count (a page may only say "Wei").
        for (m in mentions) {
            if (phrases.keys.none { it.equals(m.text, ignoreCase = true) || it.endsWith(" ${m.text}", true) }) {
                phrases[m.text] = maxOf(phrases[m.text] ?: 0, m.count)
            }
        }

        val arabicByName = pageNameSpellings(clean, pageArabic)

        val pinned = ArrayList<Pinned>(phrases.size)
        for ((name, count) in phrases.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.key.length }.thenByDescending { it.value }
        )) {
            if (pinned.size >= limit) break
            if (name.length < 2) continue
            if (name.lowercase() in STOP) continue
            val script = NameScript.of(name)
            // Compare "Han Ji-woo", "Han Ji Woo" and "han jiwoo" as the same key so a reader's
            // glossary entry matches however the OCR spelled the hyphens.
            val userHit = known.entries.firstOrNull { looseKey(it.key) == looseKey(name) }
                ?: known.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            val arabic = when {
                userHit != null -> userHit.value
                else -> arabicByName[name.lowercase()] ?: arabicByName[name.split(' ').last().lowercase()]
            }
            val resolved = when {
                userHit != null -> userHit.value
                !arabic.isNullOrBlank() -> arabic
                script != NameScript.UNKNOWN -> transliterateArabic(name, script) ?: continue
                count >= 2 -> transliterateArabic(name, script) ?: continue
                else -> continue
            }
            val origin = when {
                userHit != null && Glossary.isUserEntry(userHit.key) -> Pinned.Origin.USER
                userHit != null -> Pinned.Origin.BUILT_IN
                !arabic.isNullOrBlank() -> Pinned.Origin.PAGE_AI
                else -> Pinned.Origin.TRANSLITERATION
            }
            // The same person reached through a second spelling ("Han Ji-woo" vs "Ji-woo")
            // keeps ONE Arabic form: drop the fragment when a fuller name already pins it.
            if (pinned.any { it.text.length > name.length && sameArabic(it.arabic, resolved) }) continue
            pinned += Pinned(name, resolved, script, origin)
        }

        // Longest-first table so "Wei Wuxian" is replaced before "Wei". A name whose Arabic
        // form is already pinned by a longer variant ("Ji Woo" after "Ji-woo") is skipped.
        val table = LinkedHashMap<String, String>()
        val seenArabic = HashSet<String>()
        for (p in pinned.sortedByDescending { it.text.length }) {
            val normalizedArabic = normalizeArabic(p.arabic)
            if (normalizedArabic in seenArabic) continue
            seenArabic += normalizedArabic
            table[p.text] = p.arabic
        }
        return Plan(pinned.sortedByDescending { it.text.length }, table)
    }

    /**
     * Spellings the engine already used on this page: a name counts as "model-decided"
     * when the same Arabic token appears next to it in at least two bubbles.
     */
    private fun pageNameSpellings(texts: List<String>, pageArabic: Map<Int, String>): Map<String, String> {
        if (pageArabic.isEmpty()) return emptyMap()
        val votes = LinkedHashMap<String, MutableMap<String, Int>>()
        for ((id, arabic) in pageArabic) {
            if (arabic.isBlank()) continue
            val english = texts.getOrNull(id) ?: continue
            val names = scan(listOf(english)).map { it.text }
            if (names.isEmpty()) continue
            val arabicTokens = ARABIC_TOKEN.findAll(arabic).map { it.value }.toList()
            if (arabicTokens.isEmpty()) continue
            for (n in names) {
                // Any Latin remnant of the name still in the Arabic output is a strong hint.
                val bucket = votes.getOrPut(n.lowercase()) { LinkedHashMap() }
                for (tok in arabicTokens) {
                    if (tok.length < 3) continue
                    bucket[tok] = (bucket[tok] ?: 0) + 1
                }
            }
        }
        // Only keep spellings that repeated (≥2 bubbles); a one-off is noise.
        return votes.mapNotNull { (name, bucket) ->
            val best = bucket.entries.maxByOrNull { it.value } ?: return@mapNotNull null
            if (best.value >= 2) name to best.key else null
        }.toMap()
    }

    /**
     * True when two Arabic name forms are the same person's name: equal after removing
     * spacing/diacritics, or one is the other with the surname dropped ("هان جي وو" vs
     * "جي وو", in either order).
     */
    fun sameArabic(a: String, b: String): Boolean {
        val x = normalizeArabic(a)
        val y = normalizeArabic(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        if (x.endsWith(y) || y.endsWith(x)) return true
        if (x.startsWith(y) || y.startsWith(x)) return true
        return false
    }

    /**
     * Latin comparison key for a name: letters only, lower-cased. Hyphens, apostrophes and
     * spacing disappear, so every OCR spelling of one name compares equal.
     */
    fun looseKey(name: String): String = name.lowercase().filter { it in 'a'..'z' }

    /** Arabic comparison form: letters only, diacritics and spacing removed. */
    fun normalizeArabic(text: String): String =
        text.filter { it.code in 0x0621..0x064A }.replace(Regex("[\\u064B-\\u0652]"), "")

    private val ARABIC_TOKEN = Regex("[\\u0621-\\u064A]{2,}")
    private val NON_TEXT = Regex("[\\u0600-\\u06FF]+")

    // ---------------------------------------------------------------- transliteration

    /**
     * Deterministic Arabic form of a romanized name. Chinese goes through the pinyin
     * table in [Glossary]; Korean syllables get their own map ([NameScript] origin);
     * Japanese is approximated by a romaji table. Unknown scripts return null so the
     * caller keeps the Latin spelling instead of inventing something.
     */
    fun transliterateArabic(name: String, script: NameScript): String? = when (script) {
        NameScript.CHINESE -> name.split(' ', '-').joinToString(" ") { part ->
            Glossary.transliterateLatin(part.lowercase().replace(Regex("[^a-z]"), "")) ?: part
        }.takeIf { it.any { c -> c.code in 0x0600..0x06FF } }
        NameScript.KOREAN -> KoreanRomanizer.toArabic(name)
        NameScript.JAPANESE -> JapaneseRomanizer.toArabic(name)
        NameScript.UNKNOWN -> null
    }

    /** A short, ordered name sheet for a model that can follow instructions. */
    fun sheet(plan: Plan, limit: Int = 20): String {
        if (plan.isEmpty) return ""
        return plan.pinned.take(limit).joinToString("; ") { "${it.text} = ${it.arabic}" }
    }

    /**
     * Rewrite [arabic] so a name that the model left in Latin script carries the pinned
     * Arabic spelling. Near-variant Arabic spellings are deliberately NOT rewritten here:
     * inside Arabic prose a name is a substring of other words often enough that a blind
     * replace corrupts meaning. The sheet is what keeps the model consistent; this pass only
     * repairs the one case the guard can see (Latin remnant inside Arabic output).
     */
    fun applyPinned(arabic: String, english: String, table: Map<String, String>): String {
        if (arabic.isBlank() || table.isEmpty()) return arabic
        var out = arabic
        for ((name, pinned) in table) {
            if (name.length < 2) continue
            if (!english.contains(name, ignoreCase = true)) continue
            val latin = Regex("(?i)(?<![\\p{L}])" + Regex.escape(name) + "(?![\\p{L}])")
            if (!latin.containsMatchIn(out)) continue
            // A single-token name inside Arabic prose is safe to replace; a multi-word name
            // only when the whole phrase is present (never its first word alone).
            out = latin.replace(out, pinned)
        }
        return out
    }

    /** Names worth logging/tracing (no user secrets, just the scan result). */
    fun describe(plan: Plan): String =
        if (plan.isEmpty) "none" else plan.pinned.take(6).joinToString(", ") { "${it.text}→${it.arabic}(${it.origin.name.lowercase()})" }
}
