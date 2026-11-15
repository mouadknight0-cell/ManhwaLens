package com.manhwalens.app.translate

/**
 * Korean romanization → Arabic spelling.
 *
 * Manhwa (Korean webtoons) is the biggest slice of this app's pages, and ML Kit turns
 * "Han Ji-woo" into "هان جي وو" on one bubble and "هانجيو" on the next. This maps the
 * Revised Romanization syllables that actually appear in names to fixed Arabic forms, so
 * every bubble of a chapter spells the name the same way.
 *
 * The table is deliberately conservative: it only converts a token that it can split
 * COMPLETELY into known syllables; anything else returns null and the caller keeps the
 * Latin name (no invented spellings).
 *
 * Pure Kotlin (JVM-tested).
 */
object KoreanRomanizer {

    /** Longest-first syllable inventory (Revised Romanization, name-friendly subset). */
    private val SYLLABLES: List<Pair<String, String>> = listOf(
        // ── surnames ────────────────────────────────────────────────────────────
        "kim" to "كيم", "gim" to "كيم", "lee" to "لي", "yi" to "يي", "park" to "بارك",
        "pak" to "بارك", "choi" to "تشوي", "chwe" to "تشوي", "jung" to "جونغ",
        "jeong" to "جونغ", "chung" to "تشونغ", "kang" to "كانغ", "cho" to "تشو",
        "yoon" to "يون", "yun" to "يون", "jang" to "جانغ", "lim" to "ليم", "han" to "هان",
        "seo" to "سو", "suh" to "سو", "shin" to "شين", "sin" to "شين", "kwon" to "كوون",
        "hwang" to "هوانغ", "ahn" to "آن", "an" to "آن", "song" to "سونغ", "yoo" to "يو",
        "yu" to "يو", "hong" to "هونغ", "jeon" to "جون", "jun" to "جون", "moon" to "مون",
        "mun" to "مون", "yang" to "يانغ", "son" to "سون", "baek" to "بيك", "bae" to "بي",
        "nam" to "نام", "ryu" to "ريو", "namgung" to "نامغونغ", "hae" to "هي", "jin" to "جين",
        "cha" to "تشا", "kwak" to "كواك", "noh" to "نوه", "pyo" to "بيو", "woo" to "وو",
        "gu" to "غو", "ko" to "كو", "go" to "غو", "min" to "مين", "ki" to "كي",
        // ── given-name syllables ────────────────────────────────────────────────
        "ji" to "جي", "woo" to "وو", "woon" to "وون", "woon" to "وون", "hyun" to "هيون",
        "hyon" to "هيون", "hyun" to "هيون", "sung" to "سونغ", "seong" to "سونغ",
        "kyung" to "كيونغ", "kyeong" to "كيونغ", "soo" to "سو", "su" to "سو", "yul" to "يول",
        "eun" to "أون", "ah" to "آه", "hee" to "هي", "hui" to "هوي", "chan" to "تشان",
        "hwan" to "هوان", "yong" to "يونغ", "il" to "إيل", "sik" to "سيك", "young" to "يونغ",
        "yeong" to "يونغ", "tae" to "تاي", "dae" to "داي", "rae" to "راي", "nae" to "ناي",
        "bo" to "بو", "bom" to "بوم", "byeol" to "بيول", "seok" to "سوك", "suk" to "سوك",
        "do" to "دو", "ri" to "ري", "ra" to "را", "mi" to "مي", "na" to "نا", "ha" to "ها",
        "yo" to "يو", "ye" to "يي", "bi" to "بي", "da" to "دا", "ga" to "غا", "gyu" to "غيو",
        "hoon" to "هون", "hun" to "هون", "jae" to "جاي", "je" to "جي", "kwang" to "كوانغ",
        "gwang" to "غوانغ", "myung" to "ميونغ", "myeong" to "ميونغ", "sang" to "سانغ",
        "won" to "وون", "yeon" to "يون", "yeo" to "يو", "ju" to "جو", "joo" to "جو",
        "kyu" to "كيو", "kang" to "كانغ", "gun" to "غون", "geon" to "غون", "gyeong" to "كيونغ",
        "seul" to "سول", "sol" to "سول", "ran" to "ران", "lan" to "ران", "byeong" to "بيونغ",
        "bok" to "بوك", "cheol" to "تشول", "chul" to "تشول", "dal" to "دال", "deok" to "دوك",
        "duk" to "دوك", "gap" to "غاب", "gil" to "غيل", "guk" to "غوك", "gook" to "غوك",
        "gwak" to "غواك", "gyeom" to "غيوم", "ha" to "ها", "hyang" to "هيانغ",
        "hye" to "هيي", "hyeon" to "هيون", "hyo" to "هيو", "in" to "إن", "ip" to "إيب",
        "jeom" to "جوم", "jeong" to "جونغ", "ju" to "جو", "kuk" to "كوك", "kyeom" to "كيوم",
        "man" to "مان", "mok" to "موك", "mu" to "مو", "myeong" to "ميونغ", "nam" to "نام",
        "ok" to "أوك", "on" to "أون", "pil" to "بيل", "ryeo" to "ريو", "ryeong" to "ريونغ",
        "sam" to "سام", "se" to "سي", "shik" to "شيك", "sin" to "شين", "so" to "سو",
        "sop" to "سوب", "tae" to "تاي", "tak" to "تاك", "ui" to "أوي", "uk" to "أوك",
        "un" to "أون", "wol" to "وول", "yeol" to "يول", "yoon" to "يون", "yub" to "يوب"
    ).sortedByDescending { it.first.length }

    /** Whole names whose official spelling is not a plain syllable chain. */
    private val WHOLE = mapOf(
        "jiwoo" to "جي وو", "jihoo" to "جي هو", "jihun" to "جي هون", "jihoon" to "جي هون",
        "minjun" to "مين جون", "minho" to "مين هو", "minji" to "مين جي", "minseo" to "مين سو",
        "seoyeon" to "سو يون", "seoyeon" to "سو يون", "hayoon" to "ها يون", "haeun" to "ها أون",
        "eunji" to "أون جي", "eunwoo" to "أون وو", "soojin" to "سو جين", "sujin" to "سو جين",
        "taehyung" to "تاي هيونغ", "jimin" to "جي مين", "jungkook" to "جونغ كوك",
        "seokjin" to "سوك جين", "namjoon" to "نام جون", "yoongi" to "يون غي",
        "hoseok" to "هو سوك", "jisoo" to "جي سو", "jennie" to "جيني", "chaeyoung" to "تشاي يونغ",
        "dohyun" to "دو هيون", "doyun" to "دو يون", "siwoo" to "شي وو", "siwoo" to "شي وو",
        "hyunwoo" to "هيون وو", "junseo" to "جون سو", "junho" to "جون هو", "gunwoo" to "غون وو",
        "geonwoo" to "غون وو", "woojin" to "وو جين", "yejun" to "يي جون", "yeonjun" to "يون جون",
        "sangwoo" to "سانغ وو", "jaewon" to "جاي وون", "taeyang" to "تاي يانغ",
        "yuna" to "يونا", "yuri" to "يوري", "sora" to "سورا", "hana" to "هانا"
    )

    /**
     * Arabic spelling of a Korean romanized name, or null when the token cannot be fully
     * split into the table (then the caller must keep the Latin name).
     */
    fun toArabic(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty()) return null
        val parts = name.split(' ', '-', '_', '.').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null

        val out = ArrayList<String>(parts.size)
        for (part in parts) {
            val lower = part.lowercase().replace(Regex("[^a-z]"), "")
            if (lower.isEmpty()) return null
            WHOLE[lower]?.let { out += it; continue }
            val arabic = syllables(lower) ?: return null
            out += arabic
        }
        return out.joinToString(" ").takeIf { it.any { c -> c.code in 0x0600..0x06FF } }
    }

    /** Greedy longest-match split; null when any character cannot be consumed. */
    private fun syllables(lower: String): String? {
        if (lower.length < 2) return null
        val sb = StringBuilder()
        var i = 0
        while (i < lower.length) {
            var matched = false
            for ((syl, ar) in SYLLABLES) {
                if (lower.startsWith(syl, i)) {
                    if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' ')
                    sb.append(ar)
                    i += syl.length
                    matched = true
                    break
                }
            }
            if (!matched) return null
        }
        return sb.toString().ifBlank { null }
    }
}

/**
 * Japanese romaji → Arabic spelling, same contract as [KoreanRomanizer]: whole-token
 * conversion or null. Only the endings and clusters that appear in manga names are
 * covered; anything else keeps the Latin form.
 */
object JapaneseRomanizer {

    private val WHOLE = mapOf(
        "sasuke" to "ساسكي", "naruto" to "ناروتو", "sakura" to "ساكورا", "kakashi" to "كاكاشي",
        "itachi" to "إيتاتشي", "hinata" to "هيناتا", "hiroshi" to "هيروشي", "takeshi" to "تاكيشي",
        "kenji" to "كينجي", "ryo" to "ريو", "ryu" to "ريو", "yuki" to "يوكي", "yumi" to "يومي",
        "akira" to "أكيرا", "haruki" to "هاروكي", "haruka" to "هاروكا", "shota" to "شوتا",
        "kenta" to "كينتا", "yuto" to "يوتو", "sota" to "سوتا", "ren" to "رين", "rin" to "رين",
        "yui" to "يوي", "aoi" to "آوي", "mei" to "ميي", "sora" to "سورا", "hina" to "هينا",
        "taro" to "تارو", "jiro" to "جيرو", "saburo" to "سابورو", "ichiro" to "إيتشيرو",
        "kazuki" to "كازوكي", "shinji" to "شينجي", "asuka" to "أسوكا", "rei" to "ريي",
        "makoto" to "ماكوتو", "mio" to "ميو", "kaede" to "كايدي", "kaito" to "كايتو",
        "kyo" to "كيو", "kyoko" to "كيوكو", "ayaka" to "أياكا", "naomi" to "ناومي"
    )

    private val SYLLABLES: List<Pair<String, String>> = listOf(
        "kya" to "كيا", "kyu" to "كيو", "kyo" to "كيو", "sha" to "شا", "shu" to "شو", "sho" to "شو",
        "shi" to "شي", "she" to "شيه", "cha" to "تشا", "chu" to "تشو", "cho" to "تشو",
        "chi" to "تشي", "che" to "تشيه", "tsu" to "تسو", "tsa" to "تسا", "tso" to "تسو",
        "rya" to "ريا", "ryu" to "ريو", "ryo" to "ريو", "nya" to "نيا", "nyu" to "نيو",
        "nyo" to "نيو", "hya" to "هيا", "hyu" to "هيو", "hyo" to "هيو", "bya" to "بيا",
        "byu" to "بيو", "byo" to "بيو", "gya" to "غيا", "gyu" to "غيو", "gyo" to "غيو",
        "ja" to "جا", "ju" to "جو", "jo" to "جو", "ji" to "جي", "ka" to "كا", "ki" to "كي",
        "ku" to "كو", "ke" to "كيه", "ko" to "كو", "sa" to "سا", "su" to "سو", "se" to "سيه",
        "so" to "سو", "ta" to "تا", "te" to "تيه", "to" to "تو", "na" to "نا", "ni" to "ني",
        "nu" to "نو", "ne" to "نيه", "no" to "نو", "ha" to "ها", "hi" to "هي", "fu" to "فو",
        "he" to "هيه", "ho" to "هو", "ma" to "ما", "mi" to "مي", "mu" to "مو", "me" to "ميه",
        "mo" to "مو", "ya" to "يا", "yu" to "يو", "yo" to "يو", "ra" to "را", "ri" to "ري",
        "ru" to "رو", "re" to "ريه", "ro" to "رو", "wa" to "وا", "wo" to "وو", "ga" to "غا",
        "gi" to "غي", "gu" to "غو", "ge" to "غيه", "go" to "غو", "za" to "زا", "zu" to "زو",
        "ze" to "زيه", "zo" to "زو", "da" to "دا", "de" to "ديه", "do" to "دو", "ba" to "با",
        "bi" to "بي", "bu" to "بو", "be" to "بيه", "bo" to "بو", "pa" to "با", "pi" to "بي",
        "pu" to "بو", "pe" to "بيه", "po" to "بو", "a" to "ا", "i" to "ي", "u" to "و",
        "e" to "يه", "o" to "و", "n" to "ن"
    ).sortedByDescending { it.first.length }

    fun toArabic(raw: String): String? {
        val name = raw.trim()
        if (name.isEmpty()) return null
        val parts = name.split(' ', '-', '_').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        val out = ArrayList<String>(parts.size)
        for (part in parts) {
            val lower = part.lowercase().replace(Regex("[^a-z]"), "")
            if (lower.isEmpty()) return null
            WHOLE[lower]?.let { out += it; continue }
            val sb = StringBuilder()
            var i = 0
            while (i < lower.length) {
                var matched = false
                for ((syl, ar) in SYLLABLES) {
                    if (lower.startsWith(syl, i)) {
                        sb.append(ar)
                        i += syl.length
                        matched = true
                        break
                    }
                }
                if (!matched) return null
            }
            if (sb.isEmpty()) return null
            out += sb.toString()
        }
        return out.joinToString(" ").takeIf { it.any { c -> c.code in 0x0600..0x06FF } }
    }
}
