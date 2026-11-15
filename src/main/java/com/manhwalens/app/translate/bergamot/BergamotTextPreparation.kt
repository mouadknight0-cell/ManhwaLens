package com.manhwalens.app.translate.bergamot

/** Pure Kotlin text and batch preparation; kept separate so edge cases are JVM-testable. */
internal object BergamotTextPreparation {

    /**
     * Join OCR-wrapped lines with spaces. Remove zero-width artifacts and heal a word
     * split by a hyphen at a visual line edge. Ordinary in-line hyphens are preserved.
     */
    fun asSingleLine(source: String): String {
        val out = StringBuilder(source.length)
        var pendingSpace = false
        var index = 0
        while (index < source.length) {
            val char = source[index]
            if (char == '\u200B' || char == '\uFEFF') {
                index++
                continue
            }
            if ((char == '-' || char == '‐' || char == '–') &&
                out.isNotEmpty() && out.last().isLetter()
            ) {
                var lookahead = index + 1
                var crossedLine = false
                while (lookahead < source.length && source[lookahead].isWhitespace()) {
                    if (source[lookahead] == '\n' || source[lookahead] == '\r') crossedLine = true
                    lookahead++
                }
                if (crossedLine && lookahead < source.length && source[lookahead].isLowerCase()) {
                    index = lookahead
                    pendingSpace = false
                    continue
                }
            }
            if (char.isWhitespace() || char == '\u00A0') {
                if (out.isNotEmpty()) pendingSpace = true
            } else {
                if (pendingSpace) out.append(' ')
                out.append(char)
                pendingSpace = false
            }
            index++
        }
        return out.toString()
    }

    /**
     * Ordinary pages remain a single JNI batch. Oversized pages are split sequentially,
     * bounding temporary native buffers without parallel access to the shared service.
     */
    fun adaptiveBatches(texts: List<String>): List<List<String>> {
        if (texts.isEmpty()) return emptyList()
        val totalChars = texts.sumOf { it.length }
        val averageLength = totalChars.toDouble() / texts.size
        val maxItems = when {
            averageLength <= 100.0 -> 24
            averageLength <= 300.0 -> 16
            else -> 8
        }
        val maxChars = when {
            averageLength <= 100.0 -> 12_000
            averageLength <= 300.0 -> 8_000
            else -> 4_000
        }
        if (texts.size <= maxItems && totalChars <= maxChars) return listOf(texts)

        val batches = ArrayList<List<String>>()
        var current = ArrayList<String>()
        var chars = 0
        for (text in texts) {
            if (current.isNotEmpty() && (current.size >= maxItems || chars + text.length > maxChars)) {
                batches.add(current)
                current = ArrayList()
                chars = 0
            }
            current.add(text)
            chars += text.length
        }
        if (current.isNotEmpty()) batches.add(current)
        return batches
    }
}
