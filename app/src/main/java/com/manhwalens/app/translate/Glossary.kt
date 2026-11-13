package com.manhwalens.app.translate

/**
 * Name + term dictionary (v0.9.14) and the text preparation that runs before every engine call.
 *
 * Why: on-device ML Kit knows no manhwa name, so "Wei Lin" came back as "وي لين", "Wei Lin"
 * or "ويلين" from one bubble to the next. The fix is to resolve the name BEFORE the engine
 * sees it — [prepare] swaps a known Latin name for its Arabic form, so the engine treats it
 * as one opaque token and every bubble spells it the same way.
 *
 * The table below is Chinese-style names/terms as they appear in English fan translations.
 * The user can add his own lines in Settings (`English = عربي`, one per line); they win over
 * the built-in ones. Unknown capitalized Latin tokens that parse fully as pinyin syllables
 * are transliterated with [transliterateLatin] as a last resort.
 */
object Glossary {

    /** Settings-driven configuration (set once per engine creation by TranslatorFactory). */
    data class Config(val userText: String = "", val mergeLines: Boolean = true)

    @Volatile
    var config: Config = Config()
        private set

    private class Compiled(val map: Map<String, String>, val patterns: List<Pair<Regex, String>>)

    @Volatile
    private var compiledCache: Compiled? = null

    fun refresh(userText: String, mergeLines: Boolean) {
        config = Config(userText, mergeLines)
        compiledCache = null
    }

    /** 250 names × every block would mean 250 regex compiles per bubble — compile once. */
    private fun compiled(): Compiled = compiledCache ?: synchronized(this) {
        compiledCache ?: run {
            val map = buildEffective(config.userText)
            val patterns = map.entries.sortedByDescending { it.key.length }.map { (key, arabic) ->
                Regex("(?i)(?<![\\p{L}])" + Regex.escape(key) + "(?![\\p{L}])") to arabic
            }
            Compiled(map, patterns).also { compiledCache = it }
        }
    }

    private fun buildEffective(userText: String): Map<String, String> {
        val user = userEntries(userText)
        if (user.isEmpty()) return NAMES
        val merged = LinkedHashMap<String, String>(NAMES)
        user.forEach { (k, v) -> merged[k] = v }
        return merged
    }

    // ---------------------------------------------------------------- preparation

    /**
     * Everything an engine would otherwise have to guess: line breaks/hyphenation fixed,
     * (optionally) a multi-line bubble collapsed to one sentence, names resolved to Arabic.
     * Idempotent — an already prepared string comes back unchanged.
     */
    fun prepare(text: String, mergeLines: Boolean = config.mergeLines): String {
        val c = compiled()
        var out = BubbleTextRules.normalize(text)
        if (mergeLines) out = BubbleTextRules.oneLine(out)
        return applyCompiled(out, c)
    }

    /** Same as [prepare]: the cache must key on what the engine was actually shown. */
    fun cacheText(text: String): String = prepare(text)

    /**
     * Replace table keys (longest first, case-insensitive, whole words) whose source token is
     * capitalized or at least 4 chars long — so "he"/"ma" never eat a normal English word.
     */
    fun applyToSource(text: String, table: Map<String, String>): String {
        if (table.isEmpty() || text.isEmpty()) return text
        var out = text
        for ((key, arabic) in table.entries.sortedByDescending { it.key.length }) {
            out = replaceWord(out, key, arabic)
        }
        return transliterateUnknown(out, table)
    }

    private fun applyCompiled(text: String, c: Compiled): String {
        if (text.isEmpty()) return text
        var out = text
        for ((pattern, arabic) in c.patterns) {
            if (!pattern.containsMatchIn(out)) continue
            out = pattern.replace(out) { m -> if (ambiguousShort(m.value)) m.value else arabic }
        }
        return transliterateUnknown(out, c.map)
    }

    private fun replaceWord(text: String, key: String, arabic: String): String {
        if (key.length < 4 && !key.contains(' ') && key.length < 3) return text
        val pattern = StringBuilder("(?i)").append("(?<![\\p{L}])").append(Regex.escape(key)).append("(?![\\p{L}])").toString()
        return Regex(pattern).replace(text) { m -> if (ambiguousShort(m.value)) m.value else arabic }
    }

    /** Capitalized Latin tokens that are pure pinyin and unknown to the table → Arabic. */
    /**
     * A 3-letter table entry ("He", "Ma", "Yu") also reads as an ordinary English word, and a
     * sentence often starts with one ("He will come…"). Those are left alone; real name hits are
     * multiword or longer.
     */
    private fun ambiguousShort(token: String): Boolean =
        token.length <= 3 && token.lowercase() in ENGLISH_WORDS

    private fun transliterateUnknown(text: String, table: Map<String, String>): String {
        if (text.none { it.code in 65..122 }) return text
        return Regex("\\p{L}{3,}").replace(text) { m ->
            val token = m.value
            val lower = token.lowercase()
            when {
                lower in table -> token
                !token.first().isUpperCase() -> token
                token.all { it.isUpperCase() } -> token // SFX bursts are not names
                lower in ENGLISH_WORDS -> token
                !looksChinese(lower) -> token
                else -> transliterateLatin(lower) ?: token
            }
        }
    }

    // ---------------------------------------------------------------- dictionary

    /** user entries first (they shadow the built-ins), then the built-in names. */
    fun effective(): Map<String, String> = compiled().map

    /**
     * True when [name] comes from the reader's own settings (not the built-in table).
     * The scene planner uses this to prefer the reader's spelling over its own guess.
     */
    fun isUserEntry(name: String): Boolean =
        runCatching { userEntries(config.userText).keys.any { it.equals(name, ignoreCase = true) } }
            .getOrDefault(false)

    /** Parses "English = عربي" / "English: عربي" / "English,عربي" lines; skips junk. */
    fun userEntries(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in text.split('\n')) {
            val raw = line.trim()
            if (raw.isEmpty() || raw.startsWith("#")) continue
            val idx = raw.indexOfFirst { it == '=' || it == ':' || it == '，' || it == ',' }
            if (idx <= 0) continue
            val key = raw.substring(0, idx).trim().trim('"', '\'')
            val value = raw.substring(idx + 1).trim().trim('"', '\'')
            if (key.isEmpty() || value.isEmpty()) continue
            if (key.length > 40 || value.length > 60) continue
            if (key.none { it.code in 65..122 }) continue
            if (value.any { it.code in 65..122 }) continue
            out[key] = value
        }
        return out
    }

    /** Prompt hints for a model that can follow instructions (Gemini). */
    fun promptHints(limit: Int = 40): String =
        effective().entries.sortedByDescending { it.key.length }.take(limit)
            .joinToString("; ") { (en, ar) -> "$en = $ar" }

    // ---------------------------------------------------------------- pinyin → Arabic

    private val INITIALS = listOf(
        "zh" to "تش", "ch" to "تش", "sh" to "ش",
        "b" to "ب", "p" to "ب", "m" to "م", "f" to "ف",
        "d" to "د", "t" to "ت", "n" to "ن", "l" to "ل",
        "g" to "غ", "k" to "ك", "h" to "ه",
        "j" to "ج", "q" to "تش", "x" to "ش",
        "r" to "ر", "z" to "تس", "c" to "تس", "s" to "س",
        "y" to "ي", "w" to "و"
    )

    private val FINALS = listOf(
        "iang" to "يانغ", "iong" to "يونغ", "uang" to "وانغ", "ueng" to "ونغ", "ang" to "انغ",
        "eng" to "نغ", "ing" to "ينغ", "ong" to "ونغ", "ian" to "يان", "iao" to "ياو",
        "uai" to "واي", "uan" to "وان", "üan" to "يوان", "van" to "وان", "üe" to "يوه", "ve" to "ويه",
        "ai" to "اي", "ei" to "ي", "ao" to "او", "ou" to "و", "an" to "ان", "en" to "ن",
        "er" to "ر", "ia" to "يا", "ie" to "يه", "iu" to "يو", "in" to "ين", "un" to "ون",
        "uo" to "وو", "ua" to "وا", "ui" to "وي", "ue" to "ويه", "ü" to "يو", "v" to "و",
        "a" to "ا", "o" to "و", "e" to "ه", "i" to "ي", "u" to "و"
    )

    /** Whole syllables whose nicer Arabic form differs from initials+finals composition. */
    private val WHOLE = mapOf(
        "wei" to "وي", "hui" to "هوي", "rui" to "روي", "sui" to "سوي", "dui" to "دوي",
        "cui" to "تسوي", "zui" to "تسوي", "shui" to "شوي", "gui" to "غوي", "kui" to "كوي",
        "xue" to "شيويه", "yue" to "يوي", "jue" to "جويه", "que" to "تشويه", "lue" to "لويه",
        "yu" to "يو", "ye" to "يه", "yi" to "يي", "wu" to "وو", "wa" to "وا", "wo" to "وو",
        "ya" to "يا", "you" to "يو", "yan" to "يان", "yin" to "ين", "ying" to "يينغ",
        "yun" to "يون", "yuan" to "يوان", "yong" to "يونغ",
        "shen" to "شن", "zhen" to "تشن", "chen" to "تشن", "ren" to "رن", "wen" to "ون",
        "men" to "من", "ben" to "بن", "fen" to "فن", "gen" to "غن", "ken" to "كن",
        "ming" to "مينغ", "qing" to "تشينغ", "jing" to "جينغ", "xing" to "شينغ", "ling" to "لينغ",
        "ning" to "نينغ", "ting" to "تينغ", "bing" to "بينغ", "ping" to "بينغ", "ding" to "دينغ",
        "qiu" to "تشيو", "jiu" to "جيو", "xiu" to "شيو", "liu" to "ليو", "niu" to "نيو",
        "jun" to "جون", "qun" to "تشون", "xun" to "شون", "hun" to "هون", "lun" to "لون",
        "xuan" to "شوان", "juan" to "جوان", "quan" to "تشوان", "yuan2" to "يوان",
        "rong" to "رونغ", "song" to "سونغ", "tong" to "تونغ", "kong" to "كونغ", "hong" to "هونغ",
        "meng" to "منغ", "peng" to "بنغ", "feng" to "فنغ", "deng" to "دنغ", "zheng" to "تشنغ",
        "sheng" to "شنغ", "cheng" to "تشنغ", "zang" to "تسانغ", "hang" to "هانغ", "lang" to "لانغ",
        "le" to "له", "de" to "ده", "he" to "هه", "me" to "مه", "ne" to "نه", "ge" to "غه",
        "shi" to "شي", "zhi" to "تشي", "chi" to "تشي", "ri" to "ري", "zi" to "تسي", "ci" to "تسي", "si" to "سي"
    )

    /**
     * Deterministic pinyin → Arabic for a lower-cased token. Returns null when the token does
     * not parse as pinyin syllables (then it is left as-is — an English name is not mangled).
     */
    fun transliterateLatin(token: String): String? {
        if (token.length < 3 || token.length > 20) return null
        if (token.any { it !in 'a'..'z' }) return null
        WHOLE[token]?.let { return it }
        val sb = StringBuilder()
        var i = 0
        while (i < token.length) {
            var matched = false
            // Longest-first over the syllable inventory (initials+finals and bare finals).
            for ((syl, ar) in SYLLABLES) {
                if (token.startsWith(syl, i)) {
                    sb.append(ar)
                    i += syl.length
                    matched = true
                    break
                }
            }
            if (!matched) return null
        }
        val out = sb.toString()
        return out.ifBlank { null }
    }

    private val SYLLABLES: List<Pair<String, String>> = buildList {
        for ((ini, iniAr) in INITIALS) {
            for ((fin, finAr) in FINALS) {
                add((ini + fin) to (iniAr + finAr))
            }
        }
        for ((fin, finAr) in FINALS) add(fin to finAr)
    }.sortedByDescending { it.first.length }

    /**
     * A capitalized token is only treated as a pinyin name when it carries a letter pattern that
     * English words almost never use. Otherwise plain words ("Line", "Nice", "Mix") would be
     * turned into Arabic: the earlier rule transliterated "Line 1" into "لينه".
     */
    fun looksChinese(lower: String): Boolean {
        if (lower.length < 4) return false
        val markers = listOf("x", "q", "zh", "iao", "iong", "uang", "uai", "ü", "üe")
        return markers.any { lower.contains(it) }
    }

    /** Ordinary English words that must never be transliterated as a name. */
    private val ENGLISH_WORDS = setOf(
        "he", "we", "me", "us", "it", "is", "as", "at", "by", "on", "of", "or", "so", "no", "do",
        "if", "in", "to", "my", "be", "am", "an", "up", "oh", "ok", "ah", "eh", "ha", "hi", "yo",
        "the", "and", "you", "your", "yours", "she", "her", "his", "him", "they", "them", "their",
        "this", "that", "these", "those", "there", "here", "what", "when", "where", "who", "why",
        "how", "not", "but", "for", "with", "from", "into", "onto", "over", "under", "after",
        "before", "again", "still", "just", "only", "very", "will", "would", "could", "should",
        "can", "cannot", "did", "does", "done", "have", "has", "had", "was", "were", "are", "been",
        "sir", "madam", "lord", "lady", "master", "young", "old", "big", "small", "good", "bad",
        "yes", "now", "then", "than", "too", "also", "even", "ever", "never", "always", "please",
        "thank", "thanks", "sorry", "wait", "stop", "run", "look", "come", "go", "get", "got",
        "kill", "dead", "death", "blood", "sword", "king", "queen", "prince", "princess", "saint",
        "heaven", "hell", "demon", "god", "gods", "human", "people", "world", "life", "time",
        "day", "night", "morning", "evening", "man", "woman", "boy", "girl", "child", "children",
        "father", "mother", "brother", "sister", "son", "daughter", "friend", "enemy", "teacher",
        "student", "school", "city", "village", "mountain", "river", "forest", "sky", "earth",
        "fire", "water", "wind", "light", "dark", "darkness", "power", "strength", "magic",
        "sect", "clan", "family", "empire", "army", "soldier", "general", "captain", "guard",
        "swear", "promise", "remember", "forget", "understand", "believe", "think", "know"
    )

    /** Built-in Chinese-style names and terms as they appear in English translations. */
    val NAMES: Map<String, String> = buildMap {
        // ---- given names / surnames seen on their own
        put("Wei", "وي"); put("Wei Lin", "وي لين"); put("Lin", "لين"); put("Lin Wei", "لين وي")
        put("Xiao", "شياو"); put("Xiao Yan", "شياو يان"); put("Xiao Wu", "شياو وو")
        put("Xue", "شيويه"); put("Xue Yang", "شيويه يانغ")
        put("Yang", "يانغ"); put("Yun", "يون"); put("Yan", "يان"); put("Yao", "ياو")
        put("Yue", "يوي"); put("Yu", "يو"); put("Ying", "يينغ"); put("Yi", "يي"); put("Yin", "ين")
        put("Yuan", "يوان"); put("Zhao", "تشاو"); put("Zhao Yun", "تشاو يون")
        put("Zheng", "تشنغ"); put("Zhou", "تشو"); put("Zhu", "تشو"); put("Zhuang", "تشوانغ")
        put("Zhang", "تشانغ"); put("Zhang Wei", "تشانغ وي"); put("Chen", "تشن")
        put("Chen Wei", "تشن وي"); put("Cheng", "تشنغ"); put("Cao", "تساو"); put("Cai", "تساي")
        put("Cui", "تسوي"); put("Chang", "تشانغ"); put("Dai", "داي"); put("Deng", "دنغ")
        put("Ding", "دينغ"); put("Dong", "دونغ"); put("Duan", "دوان"); put("Fang", "فانغ")
        put("Feng", "فنغ"); put("Fu", "فو"); put("Gao", "قاو"); put("Guan", "غوان")
        put("Guo", "قوه"); put("Gu", "قو"); put("Han", "هان"); put("Hao", "هاو"); put("He", "هه")
        put("Hong", "هونغ"); put("Hou", "هو"); put("Hu", "هو"); put("Huang", "هوانغ")
        put("Hua", "هوا"); put("Jia", "جيا"); put("Jiang", "جيانغ"); put("Jian", "جيان")
        put("Jin", "جين"); put("Jing", "جينغ"); put("Kang", "كانغ"); put("Kong", "كونغ")
        put("Lan", "لان"); put("Li", "لي"); put("Li Wei", "لي وي"); put("Liang", "ليانغ")
        put("Ling", "لينغ"); put("Liu", "ليو"); put("Long", "لونغ"); put("Lu", "لو")
        put("Luo", "لوو"); put("Ma", "ما"); put("Meng", "منغ"); put("Mo", "مو"); put("Ming", "مينغ")
        put("Nan", "نان"); put("Ning", "نينغ"); put("Pan", "بان"); put("Pei", "باي")
        put("Peng", "بنغ"); put("Qi", "تشي"); put("Qian", "تشيان"); put("Qin", "تشين")
        put("Qing", "تشينغ"); put("Ren", "رن"); put("Rui", "روي"); put("Shen", "شن")
        put("Sheng", "شنغ"); put("Shi", "شي"); put("Song", "سونغ"); put("Su", "سو")
        put("Sun", "سون"); put("Tan", "تان"); put("Tang", "تانغ"); put("Tao", "تاو")
        put("Tian", "تيان"); put("Wang", "وانغ"); put("Wang Wei", "وانغ وي"); put("Wu", "وو")
        put("Xia", "شيا"); put("Xiang", "شيانغ"); put("Xin", "شين"); put("Xu", "شو")
        put("Xiong", "شيونغ"); put("Xie", "شيه"); put("Ye", "يه"); put("Zhan", "تشان")
        put("Zhi", "تشي"); put("Zhong", "تشونغ"); put("Jie", "جيه"); put("Hui", "هوي")
        // ---- Mo Dao Zu Shi / Untamed
        put("Wei Wuxian", "وي ووشيان"); put("Wei Ying", "وي يينغ"); put("Lan Wangji", "لان وانغجي")
        put("Lan Zhan", "لان تشان"); put("Lan Xichen", "لان تشيتشن"); put("Jiang Cheng", "جيانغ تشنغ")
        put("Jiang Yanli", "جيانغ يانلي"); put("Jiang Fengmian", "جيانغ فنغ ميان")
        put("Jin Zixuan", "جين تسي شوان"); put("Jin Guangyao", "جين غوانغ ياو")
        put("Jin Ling", "جين لينغ"); put("Nie Huaisang", "نيه هوايسانغ")
        put("Nie Mingjue", "نيه مينغجويه"); put("Wen Ning", "ون نينغ"); put("Wen Qing", "ون تشينغ")
        put("Wen Ruohan", "ون روهان"); put("Meng Yao", "منغ ياو"); put("Mo Xuanyu", "مو شوانيو")
        put("Xiao Xingchen", "شياو شينغتشن"); put("Song Lan", "سونغ لان")
        put("Song Zichen", "سونغ تسيتشن"); put("Jin Guangshan", "جين غوانغشان")
        // ---- Tian Guan Ci Fu
        put("Hua Cheng", "هوا تشنغ"); put("Xie Lian", "شيه ليان"); put("Feng Xin", "فنغ شين")
        put("Mu Qing", "مو تشينغ"); put("He Xuan", "هه شوان"); put("Shi Qingxuan", "شي تشينغشوان")
        put("Qi Rong", "تشي رونغ"); put("Pei Ming", "باي مينغ"); put("Ling Wen", "لينغ ون")
        put("Jun Wu", "جون وو"); put("Xuan Zhen", "شوان تشن"); put("Nan Feng", "نان فنغ")
        put("Fu Yao", "فو ياو"); put("Banyue", "بان يوي"); put("Lang Qianqiu", "لانغ تشيان تشيو")
        // ---- Scum Villain / other danmei & manhwa staples
        put("Luo Binghe", "لوو بينغه"); put("Shen Yuan", "شن يوان"); put("Shen Qingqiu", "شن تشينغتشيو")
        put("Liu Qingge", "ليو تشينغغه"); put("Shang Qinghua", "شانغ تشينغهوا")
        put("Mobei-Jun", "موبي جون"); put("Mobei Jun", "موبي جون"); put("Sha Hualing", "شا هوالينغ")
        put("Ning Yingying", "نينغ يينغيينغ"); put("Qiu Haitang", "تشيو هايتانغ")
        put("Gongyi Xiao", "غونغيي شياو"); put("Yue Qingyuan", "يوي تشينغ يوان")
        put("Zhuzhi-Lang", "تشوتشي لانغ"); put("Tianlang-Jun", "تيانلانغ جون")
        // ---- titles & terms
        put("young master", "الشاب النبيل"); put("young lady", "الآنسة النبيلة")
        put("senior brother", "الأخ الأكبر"); put("junior brother", "الأخ الأصغر")
        put("senior sister", "الأخت الكبرى"); put("junior sister", "الأخت الصغرى")
        put("shixiong", "الأخ الأكبر"); put("shidi", "الأخ الأصغر")
        put("shijie", "الأخت الكبرى"); put("shimei", "الأخت الصغرى"); put("shifu", "المعلم")
        put("sect leader", "قائد الطائفة"); put("clan leader", "قائد العشيرة")
        put("sect", "طائفة"); put("cultivation", "التنمية الروحية"); put("cultivator", "المتنوّر")
        put("immortal", "الخالد"); put("immortal one", "الخالد"); put("demon", "الشيطان")
        put("heavenly official", "الموظف السماوي"); put("ghost king", "ملك الأشباح")
        put("sword spirit", "روح السيف"); put("spiritual energy", "الطاقة الروحية")
        put("qi", "تشي"); put("dantian", "دانتيان"); put("core", "الجوهرة")
        put("golden core", "الجوهرة الذهبية"); put("pill", "الحبة"); put("alchemy", "الكيمياء الروحية")
        put("array", "المصفوفة"); put("talisman", "التميمة"); put("spirit beast", "الوحش الروحي")
    }
}
