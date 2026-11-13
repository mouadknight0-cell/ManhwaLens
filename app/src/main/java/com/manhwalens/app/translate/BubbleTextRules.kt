package com.manhwalens.app.translate

/**
 * Reading rules for a bubble's text (v0.9.14).
 *
 * Reader reports: when a bubble was split into two OCR lines, the engine translated each
 * line as a separate sentence ("…فعل. وهو…") and sometimes finished a sentence twice.
 * [oneLine] joins the lines of one bubble into ONE sentence before the engine sees it —
 * mid-line hyphens and soft line breaks are healed, a lower-case continuation keeps no
 * punctuation at the seam, and a line that is a fragment of the previous one is glued with
 * a space instead of a full stop. The translation therefore arrives as one sentence even
 * though the visual lines stay separate on screen.
 *
 * Pure Kotlin, no Android deps — unit tested.
 */
object BubbleTextRules {

    /** Trailing romanization dashes used inside a word by OCR line wrapping ("con- tinue"). */
    private fun String.endsWithHyphenBreak(): Boolean =
        length >= 2 && (last() == '-' || last() == '‐' || last() == '–') && this[length - 2].isLetter()

    /** Fixes OCR line-wrapped words and squashes whitespace. Newlines are kept. */
    fun normalize(text: String): String {
        var s = text.replace("\r\n", "\n").replace("\u00A0", " ").replace("\u200B", "")
        // "con- tinue" → "continue"
        s = Regex("(\\p{L})[-‐–]\\n\\s*(\\p{Ll})").replace(s) { "${it.groupValues[1]}${it.groupValues[2]}" }
        // "con-tinue" (no newline, lower-case) → "continue"
        s = Regex("(\\p{L})[-‐–](\\p{Ll}{2,})").replace(s) { m ->
            val prev = m.groupValues[1]
            if (prev.first().isUpperCase()) m.value else prev + m.groupValues[2]
        }
        // dangling dashes at line ends: "wait—\nfor" → "wait— for"
        s = Regex("[ \\t]+\\n").replace(s, "\n")
        s = Regex("\\n[ \\t]+").replace(s, "\n")
        s = Regex("[ \\t]{2,}").replace(s, " ")
        return s.trim()
    }

    /**
     * Collapses the lines of one bubble into a single sentence. Lines are joined with a space;
     * the previous line keeps its punctuation only when it really ends a sentence.
     */
    fun oneLine(text: String): String {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size <= 1) return lines.firstOrNull() ?: text.trim()
        val sb = StringBuilder(lines.first())
        for (next in lines.drop(1)) {
            val prev = sb.lastOrNull() ?: ' '
            when {
                // The bubble ends a sentence here: keep the mark, start the next line fresh.
                prev in ".!?…。！？" -> sb.append(' ')
                // A word was split across the line break by the letterer ("…مع-\nـه").
                prev == '-' || prev == '‐' || prev == '–' -> sb.setLength(sb.length - 1)
                // Comma / colon / semicolon at the seam: the same sentence continues.
                prev in ",;:" -> sb.append(' ')
                else -> sb.append(' ')
            }
            sb.append(next)
        }
        // A stutter like "!!" or "…" repeated at the seam is left alone on purpose.
        return Regex("\\s{2,}").replace(sb.toString(), " ").trim()
    }

    private val ARABIC = Regex("[\\u0600-\\u06FF]")

    /**
     * Arabic typography for engine output: the Arabic question mark and comma instead of the
     * Latin ones, no stray spaces around punctuation, no empty lines. Only touched when the text
     * really is Arabic, so an untranslated echo is never mangled.
     */
    fun polishArabic(text: String): String {
        var out = text.trim()
        if (out.isEmpty() || !ARABIC.containsMatchIn(out)) return out
        out = out.replace(Regex("\\s*\\?\\s*"), "؟ ").replace(Regex("\\s*;\\s*"), "؛ ")
        out = out.replace(Regex("\\s*,\\s*"), "، ")
        out = out.replace(Regex("[ \\t]{2,}"), " ")
        out = out.replace(Regex(" ؟"), "؟").replace(Regex(" ،"), "،")
        return out.trim()
    }

    /** Lines of a source bubble (used to keep the OCR's visual split when re-wrapping). */
    fun lineCount(text: String): Int = text.count { it == '\n' } + 1
}
