package com.manhwalens.app.names

import com.manhwalens.app.translate.NameMemory

/**
 * Text normalisation for character identity (P1).
 *
 * Two different jobs, kept apart on purpose:
 *
 *  - [normalize] is the **lookup key**. Letters only, lower case — so "Han Ji-woo",
 *    "han jiwoo", "HAN JI WOO" and "Han Ji—woo" are one character instead of four.
 *    It is deliberately not fuzzy: it never edits letters, drops syllables, or compares
 *    similarity. "Ji-woo" and "Ji-hoo" stay two different people.
 *  - [display] is what the reader sees, and it is [NameMemory.canonicalName] — the same
 *    canonicalisation the rest of the app already uses, so P1 does not invent a second one.
 *
 * [AMBIGUOUS_ENGLISH_WORDS] is the "Hope / May / Rose" problem: a capitalised English word
 * can be a real character name, but it is never *evidence* of one. Such a token is only
 * stored when the page proves it (repeated, or carrying a title) or the reader confirms it.
 *
 * Pure Kotlin (JVM-tested).
 */
object NameForm {

    /** English words that are also common given names — never auto-confirmed. */
    private val AMBIGUOUS_ENGLISH_WORDS = setOf(
        "hope", "may", "rose", "will", "grace", "angel", "faith", "joy", "dawn", "summer",
        "autumn", "winter", "sky", "rain", "sun", "star", "heart", "lily", "daisy", "ivy",
        "jade", "pearl", "crystal", "amber", "ruby", "mark", "bill", "jack", "sue", "art",
        "guy", "rich", "frank", "chase", "hunter", "mason", "carter", "parker", "sawyer",
        "cooper", "victor", "max", "rex", "drake", "storm", "north", "west", "east"
    )

    /** Lookup key: letters only, lower case. Stable across hyphen/space/case variations. */
    fun normalize(raw: String): String = raw.lowercase().filter { it in 'a'..'z' }

    /** Reader-facing spelling, canonicalised by the existing name pipeline. */
    fun display(raw: String): String = runCatching { NameMemory.canonicalName(raw) }.getOrDefault(raw.trim())

    /** True when the token is a capitalised English word that is also a given name. */
    fun isAmbiguousEnglishWord(raw: String): Boolean = normalize(raw) in AMBIGUOUS_ENGLISH_WORDS

    /** The reader-facing set of Latin spellings of [record], original first. */
    fun spellings(record: CharacterRecord): List<String> = record.allSpellings.filter { it.isNotBlank() }

    /**
     * Does [name] (or any of [aliases]) occur in [english] as a whole word?
     * Whole-word matching keeps "Han" from matching "Handsome" and "Wei" from "Weight".
     */
    fun appearsIn(english: String, name: String, aliases: List<String> = emptyList()): Boolean {
        if (english.isBlank() || name.isBlank()) return false
        for (candidate in listOf(name) + aliases) {
            if (candidate.isBlank()) continue
            val pattern = Regex("(?i)(?<![\\p{L}])" + Regex.escape(candidate) + "(?![\\p{L}])")
            if (pattern.containsMatchIn(english)) return true
        }
        return false
    }

    /**
     * Replace a whole-word Latin spelling with [arabic] in [text]. Mirrors
     * [NameMemory.applyPinned] but uses the character's full alias set, so an OCR variant
     * ("Han Ji Woo") is repaired by the same record as the canonical spelling.
     */
    fun replaceLatin(text: String, arabic: String, names: List<String>): String {
        if (text.isEmpty() || arabic.isBlank() || names.isEmpty()) return text
        var out = text
        for (name in names) {
            if (name.length < 2) continue
            val pattern = Regex("(?i)(?<![\\p{L}])" + Regex.escape(name) + "(?![\\p{L}])")
            if (pattern.containsMatchIn(out)) out = pattern.replace(out, arabic)
        }
        return out
    }

    /**
     * Replace a superseded Arabic spelling with the current one, whole word only.
     * Used to make a correction win over an already-cached translation.
     *
     * Deliberately exact-match on letters (after removing diacritics/spacing through
     * [NameMemory.normalizeArabic] on the *comparison*, not on the text): never a fuzzy
     * rewrite of unrelated Arabic prose.
     */
    fun replaceArabic(text: String, from: String, to: String): String {
        if (text.isEmpty() || from.isBlank() || to.isBlank()) return text
        if (from == to) return text
        if (NameMemory.normalizeArabic(from).length < 3) return text
        val pattern = Regex("(?<![\\p{L}])" + Regex.escape(from) + "(?![\\p{L}])")
        return if (pattern.containsMatchIn(text)) pattern.replace(text, to) else text
    }
}
