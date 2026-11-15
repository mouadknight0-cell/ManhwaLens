package com.manhwalens.app.translate

/**
 * A way of packing several bubbles into ONE engine call and getting them back.
 * Every strategy is pure text-in / text-out; [BatchSplitter] owns join, split and validation.
 */
enum class SplitStrategy(val id: String, val label: String) {
    AT("at3", "@@@"),
    NEWLINE("nl", "\\n"),
    DOUBLE_NEWLINE("nl2", "\\n\\n"),
    PIPE("pipe", " | "),
    HASH("hash3", "###"),
    BRACKET_NUM("num_bracket", "[1]"),
    ANGLE_NUM("num_angle", "<1>"),
    BULLET("bullet", "•");

    companion object {
        fun fromId(id: String?): SplitStrategy? = values().firstOrNull { it.id == id }
    }
}

/**
 * Joined-call batching (v0.9.9): join N sources with a [SplitStrategy], split the engine
 * reply back, and VALIDATE the mapping. A reply is accepted only when
 *  1. the segment count equals the source count,
 *  2. no segment is empty,
 *  3. every segment whose source has letters contains Arabic letters — or is the source
 *     itself, left untranslated by the engine (see [isEcho]),
 *  4. every segment's length is within 0.3x–4x of its source,
 *  5. no marker character of the strategy is left inside a segment.
 *
 * Pure Kotlin (JVM-testable). Replaces the count-only check of the old `PageBatch`.
 */
object BatchSplitter {

    data class Result(
        val ok: Boolean,
        /** Segments as split (trimmed); present even when validation failed. */
        val segments: List<String>,
        /** Number of segments that do not map (0 when [ok]). */
        val mismatched: Int,
        val reason: String
    )

    const val MIN_RATIO = 0.3
    const val MAX_RATIO = 4.0
    /** Sources shorter than this use it as the base of the upper bound ("No" → "لا أريد" is fine). */
    private const val SHORT_SOURCE_BASE = 4

    private val WS = Regex("\\s+")
    private val ARABIC_LETTER = Regex("[\\u0621-\\u064A\\u0671-\\u06D3]")
    private val AT_SPLIT = Regex("\\s*@@@\\s*")
    private val NL_SPLIT = Regex("\\r?\\n")
    private val NL2_SPLIT = Regex("\\r?\\n[ \\t]*\\r?\\n")
    private val PIPE_SPLIT = Regex("\\s*\\|\\s*")
    private val HASH_SPLIT = Regex("\\s*###\\s*")
    private val BRACKET_MARK = Regex("\\[\\s*([0-9\\u0660-\\u0669]+)\\s*\\]")
    private val ANGLE_MARK = Regex("<\\s*([0-9\\u0660-\\u0669]+)\\s*>")

    /** One-line form of a source (internal newlines would collide with line separators). */
    fun clean(source: String): String = source.replace(WS, " ").trim()

    fun join(strategy: SplitStrategy, sources: List<String>): String {
        val s = sources.map(::clean)
        return when (strategy) {
            SplitStrategy.AT -> s.joinToString("\n@@@\n")
            SplitStrategy.NEWLINE -> s.joinToString("\n")
            SplitStrategy.DOUBLE_NEWLINE -> s.joinToString("\n\n")
            SplitStrategy.PIPE -> s.joinToString(" | ")
            SplitStrategy.HASH -> s.joinToString("\n###\n")
            SplitStrategy.BRACKET_NUM -> s.mapIndexed { i, t -> "[${i + 1}] $t" }.joinToString("\n")
            SplitStrategy.ANGLE_NUM -> s.mapIndexed { i, t -> "<${i + 1}> $t" }.joinToString("\n")
            SplitStrategy.BULLET -> s.joinToString("\n") { "• $it" }
        }
    }

    /**
     * v0.9.13: can this page go out joined at all? Not when a source already contains a
     * marker character of [strategy] (an OCR'd "|" or "•" would split into an extra segment)
     * — such a page is simply sent per block, and nobody gets banned for it.
     */
    fun sourcesSafe(strategy: SplitStrategy, sources: List<String>): Boolean {
        val markers = markerChars(strategy)
        if (markers.isEmpty()) return true
        return sources.none { s -> markers.any { s.indexOf(it) >= 0 } }
    }

    /** Characters that must not survive inside a segment (unless the source had them). */
    fun markerChars(strategy: SplitStrategy): String = when (strategy) {
        SplitStrategy.AT -> "@"
        SplitStrategy.NEWLINE -> ""
        SplitStrategy.DOUBLE_NEWLINE -> "\n"
        SplitStrategy.PIPE -> "|"
        SplitStrategy.HASH -> "#"
        SplitStrategy.BRACKET_NUM -> "[]"
        SplitStrategy.ANGLE_NUM -> "<>"
        SplitStrategy.BULLET -> "•"
    }

    /** Split only — no validation. Segments are trimmed; empty ones are kept (they are errors). */
    fun rawSplit(strategy: SplitStrategy, response: String): List<String> {
        val r = response.trim()
        if (r.isEmpty()) return emptyList()
        return when (strategy) {
            SplitStrategy.AT -> AT_SPLIT.split(r).map { it.trim() }
            SplitStrategy.NEWLINE -> NL_SPLIT.split(r).map { it.trim() }
            SplitStrategy.DOUBLE_NEWLINE -> NL2_SPLIT.split(r).map { it.trim() }
            SplitStrategy.PIPE -> PIPE_SPLIT.split(r).map { it.trim() }
            SplitStrategy.HASH -> HASH_SPLIT.split(r).map { it.trim() }
            SplitStrategy.BRACKET_NUM -> splitLeading(r, BRACKET_MARK)
            SplitStrategy.ANGLE_NUM -> splitLeading(r, ANGLE_MARK)
            SplitStrategy.BULLET -> splitLeading(r, Regex("•"))
        }
    }

    /** Leading-marker formats: text before the first marker (if any) counts as an extra segment. */
    private fun splitLeading(r: String, marker: Regex): List<String> {
        val marks = marker.findAll(r).toList()
        if (marks.isEmpty()) return listOf(r)
        val out = ArrayList<String>(marks.size + 1)
        val head = r.substring(0, marks.first().range.first).trim()
        if (head.isNotEmpty()) out += head
        for ((i, m) in marks.withIndex()) {
            val end = if (i + 1 < marks.size) marks[i + 1].range.first else r.length
            out += r.substring(m.range.last + 1, end).trim()
        }
        return out
    }

    /** Numbered formats: the markers must read 1..n in order (Arabic-Indic digits accepted). */
    private fun numberingOk(strategy: SplitStrategy, response: String, expected: Int): Boolean {
        val marker = when (strategy) {
            SplitStrategy.BRACKET_NUM -> BRACKET_MARK
            SplitStrategy.ANGLE_NUM -> ANGLE_MARK
            else -> return true
        }
        val numbers = marker.findAll(response).map { m ->
            m.groupValues[1].map { c -> if (c in '\u0660'..'\u0669') '0' + (c - '\u0660') else c }
                .joinToString("").toIntOrNull() ?: -1
        }.toList()
        return numbers == (1..expected).toList()
    }

    fun validate(strategy: SplitStrategy, sources: List<String>, response: String): Result {
        val expected = sources.size
        if (expected == 0) return Result(true, emptyList(), 0, "empty input")
        val segments = rawSplit(strategy, response)
        if (segments.size != expected) {
            return Result(
                false, segments, kotlin.math.abs(segments.size - expected).coerceAtLeast(1),
                "count ${segments.size} != $expected"
            )
        }
        if (!numberingOk(strategy, response, expected)) {
            return Result(false, segments, expected, "markers out of order")
        }
        val markers = markerChars(strategy)
        var bad = 0
        var firstReason = ""
        for (i in 0 until expected) {
            val why = segmentProblem(clean(sources[i]), segments[i], markers)
            if (why != null) {
                bad++
                if (firstReason.isEmpty()) firstReason = "#${i + 1} $why"
            }
        }
        return if (bad == 0) Result(true, segments, 0, "ok") else Result(false, segments, bad, firstReason)
    }

    /** Null when the segment is an acceptable translation of [source]. */
    fun segmentProblem(source: String, segment: String, markers: String): String? {
        if (segment.isBlank()) return "empty"
        if (source.any { it.isLetter() } && !ARABIC_LETTER.containsMatchIn(segment) && !isEcho(source, segment)) {
            return "no Arabic letters"
        }
        if (source.isNotEmpty()) {
            if (segment.length < MIN_RATIO * source.length) return "too short (${segment.length}/${source.length})"
            if (segment.length > MAX_RATIO * maxOf(source.length, SHORT_SOURCE_BASE)) {
                return "too long (${segment.length}/${source.length})"
            }
        }
        for (c in markers) {
            if (segment.indexOf(c) >= 0 && source.indexOf(c) < 0) {
                return "marker '${if (c == '\n') "\\n" else c.toString()}' left inside"
            }
        }
        return null
    }

    /**
     * The engine sometimes leaves a bubble untranslated (SFX like "KRAK", names). That is the
     * same text it returns for a per-block call — the MAPPING is intact, so it is not a split
     * failure. (Device benchmark, v0.9.10: four separators were failed only by "KRAK".)
     */
    fun isEcho(source: String, segment: String): Boolean {
        fun norm(s: String) = s.filter { it.isLetterOrDigit() }.lowercase()
        val a = norm(source)
        return a.isNotEmpty() && a == norm(segment)
    }

    /** Validated segments, or null when the reply does not map 1:1. */
    fun split(strategy: SplitStrategy, sources: List<String>, response: String): List<String>? =
        validate(strategy, sources, response).takeIf { it.ok }?.segments
}
