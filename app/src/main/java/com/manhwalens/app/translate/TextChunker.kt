package com.manhwalens.app.translate

/**
 * Sentence-aware chunking for very long blocks (Part C): a block longer than
 * [MAX_CHARS] is split into sentence groups of at most ~[CHUNK_TARGET] chars so
 * the first sentences can be translated — and shown — before the tail.
 *
 * Pure Kotlin (JVM-testable).
 */
object TextChunker {

    /** Blocks longer than this are chunked. */
    const val MAX_CHARS = 400

    /** Target size per chunk (headroom under the hard limit). */
    const val CHUNK_TARGET = 380

    // Sentence boundary: after ., !, ?, ؟, …, 。 or a newline, followed by whitespace/end.
    private val BOUNDARY = Regex("(?<=[.!?…؟۔。])\\s+|\\n+")

    fun needsChunk(text: String): Boolean = text.length > MAX_CHARS

    /** Split [text] into sentences (terminators kept, boundaries consumed). */
    fun sentences(text: String): List<String> =
        BOUNDARY.split(text.trim())
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /**
     * Split [text] into chunks of at most [maxChars] characters each. Sentences
     * are kept whole when possible; a single sentence longer than [maxChars] is
     * hard-split at the limit.
     */
    fun chunk(text: String, maxChars: Int = CHUNK_TARGET): List<String> {
        val clean = text.trim()
        if (clean.length <= maxChars) return listOf(clean)
        val out = ArrayList<String>()
        var current = StringBuilder()
        for (s in sentences(clean)) {
            if (s.length > maxChars) {
                // Hard-split an over-long sentence.
                if (current.isNotEmpty()) {
                    out += current.toString().trim(); current = StringBuilder()
                }
                var i = 0
                while (i < s.length) {
                    out += s.substring(i, minOf(i + maxChars, s.length)).trim()
                    i += maxChars
                }
                continue
            }
            val candidate = if (current.isEmpty()) s.length else current.length + 1 + s.length
            if (candidate > maxChars && current.isNotEmpty()) {
                out += current.toString().trim()
                current = StringBuilder(s)
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(s)
            }
        }
        if (current.isNotEmpty()) out += current.toString().trim()
        return out.filter { it.isNotEmpty() }
    }
}
