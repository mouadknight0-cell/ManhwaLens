package com.manhwalens.app.ocr

/**
 * Sound-effect / watermark / ignore-list filter, applied to bubble blocks
 * BEFORE translation. Conservative by design: a short single-line bubble
 * (e.g. "ALRIGHT, I'LL HAVE A GLASS.") must always pass. Only unmistakable
 * watermarks, exact ignore-list hits, and pure SFX bursts are dropped.
 */
object BlockFilter {

    /** One dropped block, with the reason (surfaced in the pipeline trace). */
    data class Removal(val id: Int, val text: String, val reason: String)

    data class Result(
        val kept: List<BubbleBlock>,
        val removed: List<Removal>
    )

    private val WATERMARK_SUBSTRINGS = listOf(
        "www.", "http://", "https://",
        ".com", ".net", ".org", ".io",
        "tapas", "mangadex", "webtoon.com", "discord",
        "read at", "chapter", "ch."
    )

    /** Exact matches (case-insensitive, trimmed) to always drop. */
    private val IGNORE_LIST = setOf("sfx")

    /** Arabic Unicode ranges — such bubbles need no EN→AR translation. */
    private val ARABIC_REGEX = Regex("[\\u0600-\\u06FF\\u0750-\\u077F\\u08A0-\\u08FF\\uFB50-\\uFDFF\\uFE70-\\uFEFF]")

    private const val MAX_IGNORE_TOKENS = 3
    private const val MAX_IGNORE_CHARS = 24

    /** Classic manhwa/comic SFX cores (all-caps). */
    private val SFX_WORDS = setOf(
        "KRAK", "KRAAK", "BOOM", "BAM", "POW", "WHAM", "THUD", "CRASH",
        "SPLASH", "BANG", "KAPOW", "SNIKT", "THWIP", "STOMP", "SMASH",
        "WHUMP", "DOOM", "KLANG", "GRRR", "ROAR", "GROWL", "SLASH",
        "SLAM", "KABOOM", "CLASH", "THWACK"
    )

    /**
     * [frameHeight] > 0 enables the Part D edge-ignore: blocks sitting FULLY inside
     * the top [topPct]% or bottom [bottomPct]% bands are dropped (browser chrome,
     * status/nav bars that slipped through, app UI). Defaults keep behaviour
     * identical to earlier releases.
     *
     * [minHeightPx] and [minWordCount] enforce bubble-like blocks: a block shorter
     * than [minHeightPx] AND with fewer than [minWordCount] words is treated as
     * non-bubble chrome (isolated tiny box) and dropped — unless it already passed
     * as real content by another rule.
     *
     * The user ignore-list matches ONLY (v0.9.3):
     *   1. the WHOLE normalized block text equals a phrase (any position on the
     *      frame), or
     *   2. whole-word hits inside short blocks (<= [MAX_IGNORE_TOKENS] tokens,
     *      <= [MAX_IGNORE_CHARS] chars) whose CENTER sits in the top/bottom edge
     *      band (browser chrome at the screen edges).
     * Never substring matches: "stop" must not eat "stopwatch", and "TOP" must not
     * eat "top of the tower".
     */
    fun apply(
        blocks: List<BubbleBlock>,
        frameHeight: Int = 0,
        topPct: Float = 0f,
        bottomPct: Float = 0f,
        userIgnore: List<String> = emptyList(),
        minHeightPx: Float = 0f,
        minWordCount: Int = 0
    ): Result {
        val kept = ArrayList<BubbleBlock>(blocks.size)
        val removed = ArrayList<Removal>()
        val edgeTop = if (frameHeight > 0 && topPct > 0f) frameHeight * (topPct / 100f) else 0f
        val edgeBottom = if (frameHeight > 0 && bottomPct > 0f) frameHeight * (1f - bottomPct / 100f) else Float.MAX_VALUE
        for (block in blocks) {
            val flat = block.text.replace('\n', ' ').trim()
            val edgeReason = when {
                frameHeight > 0 && edgeTop > 0f && block.bounds.bottom <= edgeTop -> "edge-top"
                frameHeight > 0 && edgeBottom > 0f && block.bounds.top >= edgeBottom -> "edge-bottom"
                else -> null
            }
            if (edgeReason != null) {
                removed += Removal(block.id, flat.take(60), edgeReason)
                continue
            }
            val ignoreHit = matchUserIgnore(flat, block, frameHeight, edgeTop, edgeBottom, userIgnore)
            if (ignoreHit != null) {
                removed += Removal(block.id, flat.take(60), "ignore-list")
                continue
            }
            val reason = reasonFor(flat)
            if (reason != null) {
                removed += Removal(block.id, flat.take(60), reason)
                continue
            }
            // Bubble-like check: blocks below both min height AND min word count
            // are isolated tiny boxes (non-bubble chrome) — drop them.
            val wordCount = flat.split(Regex("\\s+")).filter { it.isNotBlank() }.size
            val blockHeight = block.bounds.bottom - block.bounds.top
            if (minHeightPx > 0f && minWordCount > 0 &&
                blockHeight < minHeightPx && wordCount < minWordCount
            ) {
                removed += Removal(block.id, flat.take(60), "non-bubble")
                continue
            }
            kept += block
        }
        return Result(kept, removed)
    }

    /** Normalized text for ignore matching: lowercase, newlines/tabs → space, collapsed spaces. */
    private fun normalizeIgnore(s: String): String =
        s.lowercase().replace(Regex("[\n\t]+"), " ").replace(Regex("""\s+"""), " ").trim()

    /** Whole-word (unicode-naive) containment: boundaries are non a-z0-9 or string ends. */
    private fun containsWholeWord(text: String, phrase: String): Boolean {
        if (phrase.isBlank()) return false
        val escaped = Regex.escape(phrase)
        return Regex("(^|[^a-z0-9_])$escaped($|[^a-z0-9_])").containsMatchIn(text)
    }

    /**
     * Returns the matched ignore phrase (for the reason text), or null.
     * See [apply] for the matching rules.
     */
    private fun matchUserIgnore(
        flat: String,
        block: BubbleBlock,
        frameHeight: Int,
        edgeTop: Float,
        edgeBottom: Float,
        phrases: List<String>
    ): String? {
        if (phrases.isEmpty()) return null
        val lower = normalizeIgnore(flat)
        if (lower.isEmpty()) return null
        val tokens = lower.split(' ').filter { it.isNotBlank() }
        val centerY = block.bounds.centerY
        val nearEdge = frameHeight > 0 && (centerY <= edgeTop || centerY >= edgeBottom)
        val shortEdgeText = tokens.size in 1..MAX_IGNORE_TOKENS && lower.length <= MAX_IGNORE_CHARS
        for (p in phrases) {
            val norm = normalizeIgnore(p)
            if (norm.isEmpty()) continue
            // 1) whole normalized text equals the phrase — anywhere on the frame
            if (lower == norm) return p
            // 2) whole words only — short blocks whose center sits in an edge band
            if (nearEdge && shortEdgeText && containsWholeWord(lower, norm)) return p
        }
        return null
    }

    /** Null → keep; non-null → drop with this reason. */
    private fun reasonFor(flat: String): String? {
        if (flat.isBlank()) return "empty"
        val lower = flat.lowercase()
        for (w in WATERMARK_SUBSTRINGS) {
            if (lower.contains(w)) return "watermark"
        }
        if (IGNORE_LIST.contains(lower)) return "ignore-list"

        // Already Arabic — translating again would mangle the text.
        if (ARABIC_REGEX.containsMatchIn(flat)) return "already-arabic"

        // Digits-only (tab counters, chapter numbers, ratings…) — nothing to translate.
        val lettersOrDigits = flat.count { it.isLetterOrDigit() }
        val hasLetter = flat.any { it.isLetter() }
        if (lettersOrDigits > 0 && !hasLetter) return "digits-only"

        // Pure symbols/punctuation (browser chrome icons, dividers) — no language content.
        if (lettersOrDigits == 0) return "symbols-only"

        val tokens = flat.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size > 3) return null // real sentence — keep unconditionally

        // Single token: drop only exact SFX words ("BOOM", "KRAK!").
        // Ordinary one-word dialogue ("WHAT?!", "YES!") always passes.
        if (tokens.size == 1) {
            val core = tokens[0].trimEnd('!', '?').filter { it.isLetter() }.uppercase()
            if (core.length >= 2 && SFX_WORDS.contains(core)) return "sound-effect"
            return null
        }

        // 2-3 tokens: drop only if EVERY token is a known SFX word (e.g. "KRAK BOOM!").
        if (tokens.all { SFX_WORDS.contains(it.trimEnd('!', '?').filter { ch -> ch.isLetter() }.uppercase()) }) {
            return "sound-effect"
        }
        return null
    }
}
