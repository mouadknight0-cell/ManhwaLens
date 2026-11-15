package com.manhwalens.app.translate

/**
 * Which romanization tradition a Latin name on the page comes from.
 *
 * WHY it matters: a manhwa translator must not "correct" an English word that happens to
 * start with a capital letter, but it must absolutely normalize "Han Jiwoo" / "Han Ji-woo"
 * / "Han Ji Woo" to one Arabic spelling. The origin decides (a) whether an unknown token
 * may be transliterated at all and (b) which table to use.
 *
 * Signals are ordered from strongest to weakest:
 *  - Hangul-style syllables: Ji-woo, Jiwoo, Seo-yeon, Hae-won, Min-jun (hyphen or a
 *    doubled vowel + "-n/-ng" tail), plus the Korean surname set.
 *  - Japanese romaji: long vowel "ou"/"uu", "-suke", "-taro", "-ko" endings, "sh"/"ch"/"ts".
 *  - Chinese pinyin: x/q/zh initials, "-ng" codas, "iao/iong/uang/uai" finals.
 * Pure Kotlin (JVM-tested).
 */
enum class NameScript {
    CHINESE,
    KOREAN,
    JAPANESE,
    UNKNOWN;

    companion object {

        /** Korean family names as romanized in official Revised Romanization. */
        private val KOREAN_SURNAMES = setOf(
            "kim", "lee", "yi", "park", "pak", "choi", "chwe", "jung", "jeong", "chung",
            "kang", "cho", "yoon", "yun", "jang", "lim", "im", "han", "oh", "seo", "suh",
            "shin", "sin", "kwon", "gwon", "hwang", "ahn", "an", "song", "yoo", "yu", "hong",
            "jeon", "jun", "ko", "go", "moon", "mun", "yang", "son", "baek", "paek", "bae",
            "nam", "ryu", "ki", "namgung", "namkoong", "hae", "jin", "cha", "gu", "kook",
            "kwak", "min", "no", "noh", "pyo", "sa", "suk", "tak", "woo", "wook"
        )

        /**
         * Korean given-name syllables. Only syllables that are NOT ordinary English words
         * live here — "min", "do", "go", "ha", "ah", "il", "in", "no" would otherwise turn
         * "Except" or "Minimum" into a Korean name.
         */
        private val KOREAN_SYLLABLES = setOf(
            "woo", "ji", "seo", "yeon", "hae", "jun", "hyun", "hyon", "jung", "jeong",
            "sung", "seong", "kyung", "kyeong", "hoo", "soo", "yul", "eun", "hee", "hui",
            "chan", "hwan", "yong", "sik", "young", "yeong", "tae", "dae", "rae", "nae",
            "bom", "byeol", "seok", "suk", "ri", "mi", "yo", "ye", "bi", "gyu", "hoon",
            "hun", "jae", "kwang", "gwang", "myung", "myeong", "sang", "won", "yeo", "hyo",
            "kyu", "kwon", "jeon", "ryu", "nam", "gun", "geon", "cheol", "chul", "deok",
            "gyeom", "hye", "hyeon", "ryeo", "ryeong", "seul", "sol", "uk", "wol", "yeol",
            "yub", "ok", "pil", "sam", "tak", "ui", "gap", "gil"
        )

        /** Japanese romaji endings that pinyin does not produce. */
        private val JAPANESE_ENDINGS = listOf(
            "suke", "taro", "jiro", "shiro", "kuro", "mura", "yama", "kawa", "saki", "zaki",
            "hiko", "nosuke", "emon", "bei", "ko", "to", "ta", "ki", "mi", "ri", "ya"
        )

        /** Japanese-specific letter clusters. */
        /**
         * Letter clusters that only Japanese romaji produces. "ou", "uu" and "ei" are NOT in
         * this list on purpose: "you", "please" and "except" contain them.
         */
        private val JAPANESE_MARKERS = listOf("tsu", "shi", "chi", "ryu", "kyo", "sho", "cho", "suke", "taro")

        /**
         * Pinyin markers. A bare "x" or "q" is not enough ("except", "question"), so the
         * letter must sit next to a vowel the way pinyin spells it (xi, xu, xi, qi, qu, zh).
         */
        private val PINYIN_PAIRS = listOf(
            "xi", "xu", "xa", "xe", "xo", "qi", "qu", "qa", "qe", "qo",
            "zh", "iao", "iong", "uang", "uai", "ü", "üe", "ng"
        )

        /** Classify a single name token, a full name, or a hyphenated Korean name. */
        fun of(raw: String): NameScript {
            val name = raw.trim()
            if (name.isEmpty()) return UNKNOWN
            val lower = name.lowercase().replace('’', '\'')
            val parts = lower.split(' ', '-', '_').filter { it.isNotBlank() }
            if (parts.isEmpty()) return UNKNOWN
            val joined = parts.joinToString("")

            // --- Korean: hyphenated syllable block, or a surname from the Korean set.
            if (parts.size >= 2) {
                val hyphenated = lower.contains('-')
                val koreanish = parts.count { it in KOREAN_SYLLABLES || it in KOREAN_SURNAMES }
                if (hyphenated && koreanish >= 1 && parts.any { it.length in 2..4 }) return KOREAN
                if (parts.first() in KOREAN_SURNAMES && koreanish >= 1) return KOREAN
                if (parts.first() in KOREAN_SURNAMES && parts.size == 2 && looksKoreanSyllable(parts[1])) return KOREAN
            }
            // A bare short surname ("Han", "Kim", "Nam") is also an English word or a Chinese
            // surname; only multi-syllable Korean surnames are unambiguous on their own.
            if (joined in KOREAN_SURNAMES && joined.length >= 5) return KOREAN
            if (looksKoreanGiven(lower)) return KOREAN

            // --- Chinese pinyin: initials/finals English never uses.
            if (hasAny(joined, PINYIN_PAIRS) && !hasAny(joined, listOf("tsu", "ryu", "kyo"))) return CHINESE

            // --- Japanese romaji.
            if (hasAny(joined, JAPANESE_MARKERS)) return JAPANESE
            if (JAPANESE_ENDINGS.any { joined.endsWith(it) && joined.length > it.length + 1 }) return JAPANESE

            return UNKNOWN
        }

        /**
         * Confidence-aware view of the same signals (P1).
         *
         * [of] answers "which table should transliterate this?", which only needs a guess.
         * P1 additionally needs to know HOW SURE that guess is, because a stored character
         * record must never present a guess as a confirmed spelling:
         *
         *  - multi-syllable structural evidence (hyphenated Korean blocks, pinyin initials
         *    English never produces, Japanese clusters) is trusted (>= 0.65);
         *  - a BARE surname ("Wang", "Kim", "Lee", "Lin", "Han") is explicitly NOT evidence:
         *    it is returned as UNKNOWN with low confidence, so the character stays Latin
         *    until the reader confirms it (requirement: never classify from Latin spelling
         *    alone);
         *  - nothing recognised -> UNKNOWN with a low confidence, never an invented script.
         */
        fun classify(raw: String): Evidence {
            val name = raw.trim()
            if (name.isEmpty()) return Evidence(UNKNOWN, 0f, listOf("empty"))
            val lower = name.lowercase().replace('\u2019', '\'')
            val parts = lower.split(' ', '-', '_').filter { it.isNotBlank() }
            if (parts.isEmpty()) return Evidence(UNKNOWN, 0f, listOf("empty"))
            val joined = parts.joinToString("")
            val reasons = ArrayList<String>(3)

            // --- Korean: a hyphenated syllable block is the strongest signal there is.
            if (parts.size >= 2) {
                val hyphenated = lower.contains('-')
                val koreanish = parts.count { it in KOREAN_SYLLABLES || it in KOREAN_SURNAMES }
                if (hyphenated && koreanish >= 1) {
                    reasons += "hyphenated syllable block"
                    return Evidence(KOREAN, 0.92f, reasons)
                }
                if (parts.first() in KOREAN_SURNAMES && koreanish >= 1) {
                    reasons += "Korean surname + Korean given syllable"
                    return Evidence(KOREAN, 0.85f, reasons)
                }
                if (parts.first() in KOREAN_SURNAMES && joined.length >= 5 && parts.any { it in KOREAN_SYLLABLES }) {
                    reasons += "Korean surname + Korean syllable"
                    return Evidence(KOREAN, 0.72f, reasons)
                }
            }
            if (joined.length >= 5 && joined in KOREAN_SURNAMES) {
                reasons += "multi-syllable Korean surname"
                return Evidence(KOREAN, 0.7f, reasons)
            }
            if (looksKoreanGiven(lower)) {
                reasons += "Korean vowel rhythm"
                return Evidence(KOREAN, 0.62f, reasons)
            }

            // --- A bare surname is NOT evidence: Wang / Kim / Lee / Lin / Han are English
            // words, Chinese surnames, Korean surnames and Japanese readings all at once.
            // This runs BEFORE the pinyin/Japanese letter-cluster rules on purpose: "wang"
            // and "lin" contain pinyin-only pairs ("ng"), and treating that as proof of a
            // script is exactly the "spelling alone" mistake this pipeline must not make.
            if (parts.size == 1 && joined.length <= 5 && isAmbiguousBareSurname(joined)) {
                reasons += "single ambiguous surname"
                return Evidence(UNKNOWN, 0.25f, reasons)
            }

            // --- Chinese pinyin: initials/finals English never uses.
            if (hasAny(joined, PINYIN_PAIRS) && !hasAny(joined, listOf("tsu", "ryu", "kyo"))) {
                reasons += "pinyin-only letter pair"
                // A pinyin-only pair inside a single short syllable is weaker than one in a
                // full name, but still clearly not English.
                return Evidence(CHINESE, if (parts.size >= 2) 0.82f else 0.74f, reasons)
            }
            val pinyinSyllables = parts.count { it in PINYIN_SYLLABLES }
            if (parts.size >= 2 && pinyinSyllables == parts.size) {
                reasons += "all syllables are pinyin"
                return Evidence(CHINESE, 0.7f, reasons)
            }

            // --- Japanese romaji.
            if (hasAny(joined, JAPANESE_MARKERS)) {
                reasons += "Japanese letter cluster"
                return Evidence(JAPANESE, 0.76f, reasons)
            }
            if (JAPANESE_ENDINGS.any { joined.endsWith(it) && joined.length > it.length + 1 }) {
                reasons += "Japanese ending"
                return Evidence(JAPANESE, 0.64f, reasons)
            }

            reasons += "no romanization evidence"
            return Evidence(UNKNOWN, 0.15f, reasons)
        }

        /** Result of [classify]: what the name looks like and how strongly. */
        data class Evidence(
            val script: NameScript,
            /** 0..1 — below 0.6 the script must not be treated as confirmed. */
            val confidence: Float,
            val reasons: List<String> = emptyList()
        ) {
            val isConfident: Boolean get() = confidence >= 0.6f
        }

        /**
         * Short pinyin syllable inventory used ONLY for classification (not transliteration —
         * that lives in [Glossary]). Every entry is a syllable that cannot be an ordinary
         * English word ending, so an all-pinyin multi-syllable name is safe to call Chinese.
         */
        private val PINYIN_SYLLABLES = setOf(
            "wei", "wu", "xian", "lan", "wang", "wangji", "jiang", "jin", "nie", "wen", "hua",
            "xie", "mu", "pei", "ling", "jun", "luo", "shen", "liu", "shang", "ning", "qiu",
            "yue", "xiao", "xue", "zhao", "zheng", "zhou", "zhu", "zhang", "chen", "cao", "cui",
            "deng", "fang", "feng", "gao", "guan", "guo", "hao", "hong", "huang", "jia", "kang",
            "kong", "liang", "long", "meng", "pan", "peng", "qi", "qian", "qin", "qing", "ren",
            "rui", "song", "sun", "tan", "tang", "tao", "tian", "xiang", "xin", "xu", "xiong",
            "ye", "zhan", "zhi", "zhong", "jie", "hui", "banyue", "mobei"
        )

        /**
         * Latin spellings that are a surname in at least two traditions (and often an English
         * word too), so a lone token proves nothing about the script.
         */
        private fun isAmbiguousBareSurname(joined: String): Boolean =
            joined in KOREAN_SURNAMES || joined in PINYIN_SYLLABLES || joined in AMBIGUOUS_BARE_SURNAMES

        /** Surnames that are common enough to be written both ways but are in neither list. */
        private val AMBIGUOUS_BARE_SURNAMES = setOf("lin", "wu", "su", "nam", "jin", "min")

        private fun hasAny(s: String, marks: List<String>) = marks.any { s.contains(it) }

        /** "jiwoo", "seoyeon", "minjun", "haein" — Korean blocks that pinyin never spells. */
        private fun looksKoreanGiven(lower: String): Boolean {
            if (lower.length < 4) return false
            // Consonant+vowel rhythm typical of Korean romanization: no pinyin-only letters.
            if (lower.any { it in "xq" }) return false
            val doubledVowel = Regex("[aeiou]{2}").findAll(lower).count()
            return doubledVowel >= 1 && lower.endsWith("o") || doubledVowel >= 1 && lower.endsWith("n") && lower.contains("eo")
        }

        private fun looksKoreanSyllable(s: String): Boolean {
            if (s.length !in 2..5) return false
            if (s.any { it in "xq" }) return false
            return s in KOREAN_SYLLABLES || s.contains("eo") || s.contains("woo") || s.contains("hy") || s.contains("yeo")
        }
    }
}
