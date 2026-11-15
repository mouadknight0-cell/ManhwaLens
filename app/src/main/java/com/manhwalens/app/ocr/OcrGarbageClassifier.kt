package com.manhwalens.app.ocr

import com.manhwalens.app.ocr.geometry.Rect2D

/** Why an OCR block was dropped before translation. */
enum class GarbageReason {
    NONE,
    /** Block overlaps one of our own Arabic patches (current or just removed). */
    PATCH_OVERLAP,
    /** Block overlaps one of our own windows (lens, caption, menu, pill, panel). */
    OWN_WINDOW,
    /** Block matches one of our own UI strings (caption / status / hint, EN + AR). */
    OWN_STRING,
    /** Recognizable Android status / notification / ManhwaLens notification text. */
    SYSTEM_UI,
    /** Block looks like OCR garbage (consonant-heavy all-caps Latin noise). */
    OCR_GARBAGE,
    /** Block sits in the browser chrome / status / nav ignore band. */
    CHROME,
}

/** Verdict on one OCR block: keep it for translation, or drop it with a reason. */
data class GarbageVerdict(
    val keep: Boolean,
    val reason: GarbageReason
)

/**
 * Decides whether an OCR block is not real page content and must be dropped before
 * translation:
 *  1. it overlaps one of our own Arabic patches (current or just removed) — stale
 *     Arabic burned into the frame read back as Latin garbage ("NHRAIP GAI PNG");
 *  2. it overlaps one of our own windows (lens button, caption, menu, pill, panel);
 *  3. it matches one of our own UI strings (EN + AR);
 *  4. it looks like OCR garbage — consonant-heavy all-caps Latin noise.
 *
 * Rule 1 is the reliable detector for burned-in Arabic: the garbage block sits exactly
 * where a patch was. Rule 4 is conservative (>=85% consonants, >=5 letters, all caps)
 * so real dialogue like "STOP!" is never falsely dropped.
 *
 * Pure JVM — unit-testable.
 */
class OcrGarbageClassifier(
    private val ownedStrings: Set<String>,
    private val nowMs: () -> Long
) {

    private val VOWELS = setOf('A', 'E', 'I', 'O', 'U', 'Y')

    companion object {
        /** Conservative all-caps consonant noise threshold (fraction of letters). */
        const val CONSONANT_RATIO_MIN = 0.85
        /** Below this many letters, all-caps text is treated as a real short word. */
        const val GARBAGE_MIN_LETTERS = 5
    }

    fun classify(
        text: String,
        box: Rect2D,
        patchRects: List<Rect2D>,    // current + grace-removed patch rects
        ownedRects: List<Rect2D>,    // our windows' rects
    ): GarbageVerdict {
        val flat = text.trim()
        if (flat.isEmpty()) return GarbageVerdict(true, GarbageReason.NONE)

        // 1) Overlaps a patch we (just) painted → stale Arabic burned into this frame.
        if (patchRects.any { overlaps(box, it) }) {
            return GarbageVerdict(false, GarbageReason.PATCH_OVERLAP)
        }

        // 2) Overlaps one of our own windows.
        if (ownedRects.any { overlaps(box, it) }) {
            return GarbageVerdict(false, GarbageReason.OWN_WINDOW)
        }

        // 3) Matches our own UI strings (caption / status / hint, EN + AR).
        if (ownedStrings.contains(flat.lowercase())) {
            return GarbageVerdict(false, GarbageReason.OWN_STRING)
        }

        // 4) High-confidence Android status/notification text that can leak into full-screen
        // captures. Keep these signatures narrow; generic words like "today" are not enough.
        if (isSystemUi(flat)) {
            return GarbageVerdict(false, GarbageReason.SYSTEM_UI)
        }

        // 5) Consonant-heavy all-caps Latin noise (OCR misread of burned Arabic).
        if (isConsonantHeavyGarbage(flat)) {
            return GarbageVerdict(false, GarbageReason.OCR_GARBAGE)
        }

        return GarbageVerdict(true, GarbageReason.NONE)
    }

    private fun overlaps(a: Rect2D, b: Rect2D): Boolean =
        a.horizontalOverlap(b) > 0f && a.bottom > b.top && a.top < b.bottom

    /**
     * Narrow fingerprints for Android status/notification UI observed in full-screen
     * captures. Never drop a block on "today" or "ManhwaLens" alone: require a known
     * data-usage format, carrier + clock, or a recognizable app-notification phrase.
     */
    private fun isSystemUi(text: String): Boolean {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        val lower = flat.lowercase()
        val hasClock = Regex("\\b\\d{1,2}:\\d{2}\\b").containsMatchIn(flat)
        val hasCarrier = Regex("\\b(?:djezzy|mobilis|ooredoo)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(flat)
        if (hasClock && hasCarrier) return true

        if (Regex(
                "^(?:today|this month)\\s*:\\s*\\d+(?:[.,]\\d+)?\\s*(?:kb|mb|gb|tb)$",
                RegexOption.IGNORE_CASE
            ).matches(flat)
        ) return true

        if (lower.startsWith("manhwalens") &&
            (lower.contains("capture ready") || lower.contains("display") || lower.contains("tap the lens"))
        ) return true

        return false
    }

    /**
     * True when [text] is all-caps Latin, long enough to matter, and dominated by
     * consonants — i.e. it cannot be a real word and is likely OCR noise.
     * Conservative: real all-caps dialogue ("STOP!", "YES!", "NO!") has a vowel and
     * is short, so it passes through.
     */
    private fun isConsonantHeavyGarbage(text: String): Boolean {
        val letters = text.toList().filter { it.isLetter() }
        if (letters.size < GARBAGE_MIN_LETTERS) return false
        if (letters.any { !it.isUpperCase() }) return false
        // A real English word such as STRENGTH can be consonant-heavy without being OCR
        // debris. Require a vowel-free token sequence as well as the ratio; Y counts as a
        // vowel (RHYTHM must survive). Random OCR output like "NPHGRK ZXCV" remains rejected.
        if (letters.any { VOWELS.contains(it) }) return false
        val consonants = letters.count { !VOWELS.contains(it) }
        return consonants / letters.size.toDouble() >= CONSONANT_RATIO_MIN
    }
}
