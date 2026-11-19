package com.manhwalens.app.util

/**
 * End-to-end latency tracing, one [Pass] per translation pass (T0..T7 of the performance spec).
 *
 * Pure Kotlin (no Android types) so it is JVM-testable. Stores timings and counts only —
 * never OCR or translation text, so the report is safe to copy and share.
 *
 *  T0 scan started        T4 grouping / sentence reconstruction done
 *  T1 capture done        T5 translation started
 *  T2 preprocess done     T6 first complete Arabic sentence displayed
 *  T3 OCR done            T7 all detected dialogue displayed
 *
 * First Complete Sentence Latency = T6 - T0, Full Translation Latency = T7 - T0.
 * For Live mode, "settle wait" (time the page needed to become stable) is recorded
 * separately in [Pass.settleMs]; it is NOT part of T0..T7.
 *
 * Percentiles use the nearest-rank method on integers (no floating-point rounding).
 */
object E2ETrace {

    const val UNSET = -1L
    private const val MAX_PASSES = 200

    val SYSTEM_CLOCK: () -> Long = { System.nanoTime() / 1_000_000L }

    /** Injectable clock (ms, monotonic). Tests replace it. */
    @Volatile
    var clock: () -> Long = SYSTEM_CLOCK

    enum class Mark {
        T0_SCAN_START,
        T1_CAPTURE_DONE,
        T2_PREPROCESS_DONE,
        T3_OCR_DONE,
        T4_GROUPED,
        T5_TRANSLATE_START,
        T6_FIRST_SENTENCE,
        T7_ALL_DISPLAYED
    }

    class Pass internal constructor(val id: Long, val mode: String) {
        internal val stamps = LongArray(Mark.values().size) { UNSET }

        /** Live only: ms the page needed to become stable before this pass started. */
        @Volatile var settleMs: Long = UNSET

        /** Unique bubbles sent to translation. */
        @Volatile var blocks: Int = 0

        /** Bubbles that came back with usable Arabic. */
        @Volatile var translated: Int = 0

        @Volatile var failed: Boolean = false
        @Volatile internal var finished: Boolean = false

        fun at(mark: Mark): Long = stamps[mark.ordinal]

        /** Duration between two marks, or [UNSET] when either is missing. */
        fun span(from: Mark, to: Mark): Long {
            val a = at(from)
            val b = at(to)
            return if (a == UNSET || b == UNSET) UNSET else b - a
        }

        /** A pass counts for the latency statistics only when T0, T6 and T7 are all present. */
        val complete: Boolean
            get() = !failed &&
                at(Mark.T0_SCAN_START) != UNSET &&
                at(Mark.T6_FIRST_SENTENCE) != UNSET &&
                at(Mark.T7_ALL_DISPLAYED) != UNSET
    }

    data class Stats(val n: Int, val median: Long, val p95: Long, val max: Long)

    private val lock = Any()
    private val finished = ArrayList<Pass>()
    private var nextId = 1L
    private var pending: Pass? = null

    /** Start a pass; T0 is stamped now. */
    fun begin(mode: String, settleMs: Long = UNSET): Pass = synchronized(lock) {
        val pass = Pass(nextId++, mode)
        pass.stamps[Mark.T0_SCAN_START.ordinal] = clock()
        pass.settleMs = settleMs
        pass
    }

    /**
     * Live hand-off: the driver decides "stable, translate" on one thread and the pass runs on
     * another. The driver calls [beginPending]; the translation job calls [takePending].
     */
    fun beginPending(mode: String, settleMs: Long = UNSET) {
        synchronized(lock) { pending = begin(mode, settleMs) }
    }

    fun peekPending(): Pass? = synchronized(lock) { pending }

    fun takePending(): Pass? = synchronized(lock) {
        val p = pending
        pending = null
        p
    }

    /** Stamp [mark] now. First stamp wins, so T6 keeps the FIRST displayed sentence. */
    fun mark(pass: Pass?, mark: Mark) {
        if (pass == null) return
        synchronized(lock) {
            val i = mark.ordinal
            if (pass.stamps[i] == UNSET) pass.stamps[i] = clock()
        }
    }

    /** Everything appeared at once (Live paints the whole page together): T6 and T7 together. */
    fun markAllDisplayed(pass: Pass?) {
        mark(pass, Mark.T6_FIRST_SENTENCE)
        mark(pass, Mark.T7_ALL_DISPLAYED)
    }

    /** Close a pass and keep it for the report (ring buffer of the last [MAX_PASSES]). */
    fun finish(pass: Pass?, failed: Boolean = false) {
        if (pass == null) return
        synchronized(lock) {
            if (failed) pass.failed = true
            if (!pass.finished) {
                pass.finished = true
                finished.add(pass)
                while (finished.size > MAX_PASSES) finished.removeAt(0)
            }
        }
    }

    fun snapshot(): List<Pass> = synchronized(lock) { finished.toList() }

    fun reset() {
        synchronized(lock) {
            finished.clear()
            pending = null
            nextId = 1L
        }
    }

    /** Nearest-rank percentile on an ascending list. [percent] is an integer 1..100. */
    fun percentile(sorted: List<Long>, percent: Int): Long {
        if (sorted.isEmpty()) return UNSET
        val rank = ((percent * sorted.size + 99) / 100).coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    /** Stats over the non-negative values, or null when there are none. */
    fun stats(values: List<Long>): Stats? {
        val v = values.filter { it >= 0L }.sorted()
        if (v.isEmpty()) return null
        return Stats(v.size, percentile(v, 50), percentile(v, 95), v.last())
    }

    /** Human-readable report. [mode] filters by "live" / "tap"; null = every pass. */
    fun report(mode: String? = null): String {
        val all = snapshot().filter { mode == null || it.mode == mode }
        val tag = if (mode != null) " [$mode]" else ""
        if (all.isEmpty()) return "E2E latency$tag: no passes recorded"

        val complete = all.filter { it.complete }
        val failed = all.count { it.failed }
        val incomplete = all.count { !it.failed && !it.complete }

        val sb = StringBuilder()
        sb.append("E2E latency (T0..T7)").append(tag)
            .append(": passes=").append(all.size)
            .append(" complete=").append(complete.size)
            .append(" incomplete=").append(incomplete)
            .append(" failed=").append(failed)
            .append(" · timings and counts only\n")
        sb.append(line("first sentence after stable point (T6-T0)", complete.map { it.span(Mark.T0_SCAN_START, Mark.T6_FIRST_SENTENCE) })).append('\n')
        sb.append(line("last frame change to first sentence (settle + T6-T0)", complete.map {
            if (it.settleMs == UNSET) UNSET else it.settleMs + it.span(Mark.T0_SCAN_START, Mark.T6_FIRST_SENTENCE)
        })).append('\n')
        sb.append(line("full screen after stable point (T7-T0)", complete.map { it.span(Mark.T0_SCAN_START, Mark.T7_ALL_DISPLAYED) })).append('\n')
        sb.append(line("last frame change to full display (settle + T7-T0)", complete.map {
            if (it.settleMs == UNSET) UNSET else it.settleMs + it.span(Mark.T0_SCAN_START, Mark.T7_ALL_DISPLAYED)
        })).append('\n')

        val stages = listOf(
            Triple("capture T0>T1", Mark.T0_SCAN_START, Mark.T1_CAPTURE_DONE),
            Triple("preprocess T1>T2", Mark.T1_CAPTURE_DONE, Mark.T2_PREPROCESS_DONE),
            Triple("ocr T2>T3", Mark.T2_PREPROCESS_DONE, Mark.T3_OCR_DONE),
            Triple("grouping T3>T4", Mark.T3_OCR_DONE, Mark.T4_GROUPED),
            Triple("request prep T4>T5", Mark.T4_GROUPED, Mark.T5_TRANSLATE_START),
            Triple("translate+first display T5>T6", Mark.T5_TRANSLATE_START, Mark.T6_FIRST_SENTENCE),
            Triple("remaining display T6>T7", Mark.T6_FIRST_SENTENCE, Mark.T7_ALL_DISPLAYED)
        )
        for ((label, a, b) in stages) {
            sb.append("  ").append(line(label, complete.map { it.span(a, b) })).append('\n')
        }
        sb.append(line("settle wait (before T0)", all.map { it.settleMs })).append('\n')

        val blocks = stats(all.map { it.blocks.toLong() })
        val translated = all.sumOf { it.translated }
        val sent = all.sumOf { it.blocks }
        sb.append("blocks per pass: median ").append(blocks?.median ?: 0L)
            .append(" · translated ").append(translated).append('/').append(sent).append('\n')
        sb.append("failure rate: ").append(failed * 100 / all.size).append("% (")
            .append(failed).append('/').append(all.size).append(')')
        return sb.toString()
    }

    private fun line(label: String, values: List<Long>): String {
        val s = stats(values) ?: return "$label: n/a"
        return "$label: median ${s.median} · P95 ${s.p95} · max ${s.max} ms (n=${s.n})"
    }
}

