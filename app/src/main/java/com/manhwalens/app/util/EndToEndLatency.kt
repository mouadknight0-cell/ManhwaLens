package com.manhwalens.app.util

import kotlin.math.ceil

/**
 * v0.13.0 — single recorder for the mandatory end-to-end translation latency events.
 *
 * One "run" is one screen scan: from the moment the scan is initiated (T0) until every
 * detected dialogue line is translated and on screen (T7). Milestones:
 *
 *  T0  scan initiated               (captureFrame() starts)
 *  T1  screen capture completed     (frame copied into a Bitmap)
 *  T2  image preprocessing done     (crop/resize for OCR; everything before the engine sees the frame)
 *  T3  OCR completed                (recognized text back from the recognizer)
 *  T4  grouping/reconstruction done (bubbles grouped, filtered, ready to translate)
 *  T5  translation execution started
 *  T6  first complete Arabic sentence displayed
 *  T7  all detected dialogue translated and displayed
 *
 * Derived metrics (see [Summary]): First Sentence Latency = T6 - T0, Full Translation
 * Latency = T7 - T0, per-stage medians, worst/P95 across a bounded ring of recent runs,
 * plus dialogue-volume counters. [report] renders a key-free multi-line block that can be
 * pasted into a bug report — it contains NO page text.
 *
 * Pure Kotlin + an injectable clock so the math is JVM-unit-testable. Marks with no active
 * run are ignored (Live mode never starts a run). Re-marked events keep the FIRST timestamp:
 * the first occurrence is what the user perceives.
 */
object EndToEndLatency {

    enum class Event(val wire: String) {
        T0_SCAN_INITIATED("T0_SCAN_INITIATED"),
        T1_CAPTURE_COMPLETE("T1_CAPTURE_COMPLETE"),
        T2_PREPROCESS_COMPLETE("T2_PREPROCESS_COMPLETE"),
        T3_OCR_COMPLETE("T3_OCR_COMPLETE"),
        T4_GROUPING_COMPLETE("T4_GROUPING_COMPLETE"),
        T5_TRANSLATION_STARTED("T5_TRANSLATION_STARTED"),
        T6_FIRST_SENTENCE_DISPLAYED("T6_FIRST_SENTENCE_DISPLAYED"),
        T7_ALL_DISPLAYED("T7_ALL_DISPLAYED")
    }

    private val ORDER = Event.values().toList()

    /** One scan's raw milestones (ms on [clock]). */
    data class Run(val id: Long, val marks: Map<Event, Long>) {
        fun at(event: Event): Long? = marks[event]

        /** T6 - T0 (First Sentence Latency), or null when no sentence was painted. */
        fun firstSentenceMs(): Long? {
            val t0 = marks[Event.T0_SCAN_INITIATED] ?: return null
            val t6 = marks[Event.T6_FIRST_SENTENCE_DISPLAYED] ?: return null
            return (t6 - t0).coerceAtLeast(0L)
        }

        /** T7 - T0 (Full Translation Latency), or null when the run never settled. */
        fun fullMs(): Long? {
            val t0 = marks[Event.T0_SCAN_INITIATED] ?: return null
            val t7 = marks[Event.T7_ALL_DISPLAYED] ?: return null
            return (t7 - t0).coerceAtLeast(0L)
        }

        /** Duration of one stage: this event's mark minus the previous event's mark. */
        fun stageMs(event: Event): Long? {
            val idx = ORDER.indexOf(event)
            if (idx <= 0) return null
            val start = marks[ORDER[idx - 1]] ?: return null
            val end = marks[event] ?: return null
            return (end - start).coerceAtLeast(0L)
        }
    }

    /** Aggregate over the bounded ring of recent runs. Nulls mean "not enough data yet". */
    data class Summary(
        val completedRuns: Int,
        val failedRuns: Int,
        val firstSentenceMedianMs: Long?,
        val firstSentenceP95Ms: Long?,
        val firstSentenceWorstMs: Long?,
        val fullMedianMs: Long?,
        val fullP95Ms: Long?,
        val fullWorstMs: Long?,
        /** Median duration of every stage that at least one run completed, keyed by stage event. */
        val stageMedianMs: Map<Event, Long>,
        /** Totals since process start (or [resetStats]). */
        val totalDialogueRegions: Long,
        val totalTranslatedSentences: Long,
        /** failed / attempted across all runs that reached the translation stage; null when none did. */
        val translationFailureRate: Double?
    )

    private const val MAX_RUNS = 100

    private val lock = Any()
    private val runs = ArrayDeque<Run>()
    private var failedRuns = 0
    private var currentId = 0L
    private var current: LinkedHashMap<Event, Long>? = null
    private var dialogueRegions = 0L
    private var translatedSentences = 0L
    private var translationAttempts = 0L
    private var translationFailures = 0L

    /** Injectable clock (unit tests). Milliseconds. */
    var clock: () -> Long = { System.nanoTime() / 1_000_000L }

    /** Start a new run (T0). A previous run that never finished counts as failed. */
    fun beginRun() {
        synchronized(lock) {
            if (current != null) failedRuns += 1
            currentId += 1
            val fresh = LinkedHashMap<Event, Long>(ORDER.size)
            fresh[Event.T0_SCAN_INITIATED] = clock()
            current = fresh
        }
    }

    /** Mark [event] for the active run. First mark wins; ignored with no active run. */
    fun mark(event: Event) {
        synchronized(lock) {
            val c = current ?: return
            if (!c.containsKey(event)) c[event] = clock()
        }
    }

    /** Snapshot of the active run (debug/diagnostics), or null when idle. */
    fun currentRun(): Run? = synchronized(lock) {
        current?.let { Run(currentId, LinkedHashMap(it)) }
    }

    /** Count dialogue volume for this run (called once per scan that attempted translation). */
    fun noteDialogueStats(regions: Int, translated: Int, failed: Boolean) {
        synchronized(lock) {
            dialogueRegions += regions.coerceAtLeast(0).toLong()
            translatedSentences += translated.coerceAtLeast(0).toLong()
            translationAttempts += 1
            if (failed) translationFailures += 1
        }
    }

    /**
     * End the active run: a run that reached T6 or T7 is recorded into the stats ring;
     * anything else counts as failed (and is excluded from latency percentiles).
     * Returns the finished run, or null when no run was active.
     */
    fun finishRun(): Run? = synchronized(lock) {
        val c = current ?: return null
        current = null
        val run = Run(currentId, LinkedHashMap(c))
        if (run.at(Event.T6_FIRST_SENTENCE_DISPLAYED) != null ||
            run.at(Event.T7_ALL_DISPLAYED) != null
        ) {
            runs.addLast(run)
            while (runs.size > MAX_RUNS) runs.removeFirst()
        } else {
            failedRuns += 1
        }
        run
    }

    /** Completed (recorded) runs in the ring, oldest first. */
    fun completedRuns(): List<Run> = synchronized(lock) { runs.toList() }

    fun stats(): Summary = synchronized(lock) { summaryFor(runs.toList(), failedRuns) }

    private fun percentile(sorted: List<Long>, p: Double): Long? {
        if (sorted.isEmpty()) return null
        if (p <= 50.0) {
            if (sorted.size % 2 == 1) return sorted[sorted.size / 2]
            val a = sorted[sorted.size / 2 - 1]
            val b = sorted[sorted.size / 2]
            return (a + b) / 2
        }
        val rank = ceil(p / 100.0 * sorted.size).toInt().coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun summaryFor(list: List<Run>, failed: Int): Summary {
        val first = list.mapNotNull { it.firstSentenceMs() }.sorted()
        val full = list.mapNotNull { it.fullMs() }.sorted()
        val stageMedian = LinkedHashMap<Event, Long>()
        for (stage in ORDER) {
            val values = list.mapNotNull { it.stageMs(stage) }.sorted()
            if (values.isNotEmpty()) percentile(values, 50.0)?.let { stageMedian[stage] = it }
        }
        return Summary(
            completedRuns = list.size,
            failedRuns = failed,
            firstSentenceMedianMs = percentile(first, 50.0),
            firstSentenceP95Ms = percentile(first, 95.0),
            firstSentenceWorstMs = first.lastOrNull(),
            fullMedianMs = percentile(full, 50.0),
            fullP95Ms = percentile(full, 95.0),
            fullWorstMs = full.lastOrNull(),
            stageMedianMs = stageMedian,
            totalDialogueRegions = dialogueRegions,
            totalTranslatedSentences = translatedSentences,
            translationFailureRate = if (translationAttempts > 0) {
                translationFailures.toDouble() / translationAttempts.toDouble()
            } else {
                null
            }
        )
    }

    /** Clear the ring and the counters (diagnostics / "reset statistics"). */
    fun resetStats() {
        synchronized(lock) {
            runs.clear()
            failedRuns = 0
            dialogueRegions = 0
            translatedSentences = 0
            translationAttempts = 0
            translationFailures = 0
        }
    }

    /** Clear everything, including any in-flight run. Unit tests call this between cases. */
    fun resetAll() {
        synchronized(lock) {
            current = null
        }
        resetStats()
    }

    /** Key-free multi-line block for the pipeline report / bug reports. Contains NO page text. */
    fun report(): String {
        val s = stats()
        fun ms(v: Long?): String = v?.let { "$it ms" } ?: "n/a"
        return buildString {
            appendLine("end-to-end latency — last ${s.completedRuns} run(s) (failed: ${s.failedRuns})")
            appendLine("first sentence (T6-T0): median ${ms(s.firstSentenceMedianMs)} · p95 ${ms(s.firstSentenceP95Ms)} · worst ${ms(s.firstSentenceWorstMs)}")
            appendLine("full translation (T7-T0): median ${ms(s.fullMedianMs)} · p95 ${ms(s.fullP95Ms)} · worst ${ms(s.fullWorstMs)}")
            if (s.stageMedianMs.isNotEmpty()) {
                append("stage medians: ")
                var firstStage = true
                for ((event, median) in s.stageMedianMs) {
                    if (!firstStage) append(" · ")
                    firstStage = false
                    append("${event.wire.substring(0, 2)}=${median}ms")
                }
                appendLine()
            }
            appendLine("dialogue regions: ${s.totalDialogueRegions} · translated sentences: ${s.totalTranslatedSentences} · translation failure rate: " +
                (s.translationFailureRate?.let { String.format(java.util.Locale.US, "%.1f%%", it * 100.0) } ?: "n/a"))
        }.trim()
    }
}
