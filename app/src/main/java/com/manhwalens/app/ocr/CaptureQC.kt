package com.manhwalens.app.ocr

/**
 * Quality-control checks for capture/translation output (Part 2).
 *
 * Pure JVM — no Android types — unit-testable on the host.
 */
object CaptureQC {

    /**
     * True when [text] looks like a sentence cut off mid-way: longer than [MIN_LEN]
     * and ending without any terminal punctuation.
     * Used to decide whether the tap path should retry once with a longer hide.
     */
    private const val MIN_LEN = 12

    fun looksCut(text: String): Boolean {
        val t = text.trim()
        return t.length > MIN_LEN && !t.any { it in ".!?…。؟”\"\')]" }
    }
}
