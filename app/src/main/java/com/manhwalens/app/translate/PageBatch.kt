package com.manhwalens.app.translate

/**
 * Joined single-call batching (Part C). Blocks are joined with a UNIQUE
 * separator line ("\n@@@\n") that the model cannot reasonably emit by itself,
 * sent in ONE request (the model sees surrounding bubbles as context), and
 * split back by the same separator. Split count mismatch ⇒ null ⇒ caller
 * falls back to per-block translation.
 *
 * Pure Kotlin (JVM-testable).
 */
object PageBatch {

    const val DELIMITER = "\n@@@\n"
    private val SPLIT_REGEX = Regex("\\s*@@@\\s*")

    fun join(blocks: List<TranslationBlock>): String =
        blocks.joinToString(DELIMITER) { it.english.trim() }

    /**
     * Split a model response back into exactly [blockCount] parts.
     * Tolerates arbitrary whitespace/newlines around the @@@ marker.
     * Returns null when the part count does not match.
     */
    fun split(response: String, blockCount: Int): List<String>? {
        if (blockCount <= 0) return emptyList()
        val parts = SPLIT_REGEX.split(response.trim()).map { it.trim() }
        return if (parts.size == blockCount) parts else null
    }
}
