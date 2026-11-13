package com.manhwalens.app.live

/**
 * In-memory ring of the last [capacity] Live-relevant log lines (v0.9.5 · Part 2).
 *
 * WHY: the self-check "Copy report" must work even when `logcat` is unavailable to the app
 * (no READ_LOGS on API 16+, OEM restrictions). Everything the Live path logs through
 * `LiveLog` is kept here, key-scrubbed, and appended to the report.
 *
 * Pure JVM (no android.util.Log) so the scrubbing and the ring bound are unit-testable.
 */
class LiveLogRing(private val capacity: Int = DEFAULT_CAPACITY) {

    data class Entry(val atMs: Long, val level: String, val tag: String, val message: String) {
        fun line(): String = "$level/$tag: ${scrub(message)}"
    }

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun add(level: String, tag: String, message: String, atMs: Long = System.currentTimeMillis()) {
        entries.addLast(Entry(atMs, level, tag, message))
        while (entries.size > capacity) entries.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() = entries.clear()

    /** Plain-text dump (newest last), scrubbed. */
    @Synchronized
    fun text(): String = entries.joinToString("\n") { it.line() }

    companion object {
        /** "the last 60 log lines of tag MLCapture/ManhwaLens". */
        const val DEFAULT_CAPACITY = 60

        private val KEY_PATTERNS = listOf(
            Regex("AIza[0-9A-Za-z_-]{10,}"),
            Regex("(?i)(api[-_ ]?key|apikey|x-goog-api-key|authorization)\\s*[=:]\\s*\\S+"),
            Regex("(?i)bearer\\s+[A-Za-z0-9._-]{16,}")
        )

        /** Scrub API keys / auth headers before anything reaches the UI or the clipboard. */
        fun scrub(text: String): String {
            var out = text
            out = KEY_PATTERNS[0].replace(out, "[redacted-key]")
            out = KEY_PATTERNS[1].replace(out, "$1=[redacted]")
            out = KEY_PATTERNS[2].replace(out, "bearer [redacted]")
            return out
        }
    }
}
