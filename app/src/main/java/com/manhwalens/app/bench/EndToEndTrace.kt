package com.manhwalens.app.bench

import java.util.Locale
import kotlin.math.ceil

/**
 * End-to-End translation latency record (T0..T7) for one screen pass.
 *
 * Event definitions:
 *  - T0: Screen scanning initiated
 *  - T1: Screen capture completed
 *  - T2: Image preprocessing completed
 *  - T3: OCR completed
 *  - T4: Text grouping and sentence reconstruction completed
 *  - T5: Translation execution started
 *  - T6: First complete Arabic sentence displayed (null if 0 sentences displayed)
 *  - T7: All detected dialogue successfully translated and displayed
 *
 * Pure Kotlin (JVM-testable).
 */
data class EndToEndRunRecord(
    val label: String,
    val mode: String, // "tap" | "live" | "benchmark"
    val t0Ms: Long,
    val t1Ms: Long,
    val t2Ms: Long,
    val t3Ms: Long,
    val t4Ms: Long,
    val t5Ms: Long,
    val t6Ms: Long?,
    val t7Ms: Long,
    val detectedRegions: Int,
    val translatedSentences: Int,
    val failedSentences: Int,
    val translationFailed: Boolean,
    val cpuTimeMs: Long = -1L,
    val memoryUsedKb: Long = -1L
) {
    /** First Complete Sentence Latency = T6 - T0 (null if no sentence was displayed). */
    val firstSentenceLatencyMs: Long?
        get() = t6Ms?.let { (it - t0Ms).coerceAtLeast(0L) }

    /** Full Translation Latency = T7 - T0. */
    val fullTranslationLatencyMs: Long
        get() = (t7Ms - t0Ms).coerceAtLeast(0L)

    val captureMs: Long get() = (t1Ms - t0Ms).coerceAtLeast(0L)
    val preprocessMs: Long get() = (t2Ms - t1Ms).coerceAtLeast(0L)
    val ocrMs: Long get() = (t3Ms - t2Ms).coerceAtLeast(0L)
    val groupingReconstructionMs: Long get() = (t4Ms - t3Ms).coerceAtLeast(0L)
    val translationPrepMs: Long get() = (t5Ms - t4Ms).coerceAtLeast(0L)
    val firstDisplayAfterTranslateStartMs: Long?
        get() = t6Ms?.let { (it - t5Ms).coerceAtLeast(0L) }
    val translationAndDisplayMs: Long get() = (t7Ms - t5Ms).coerceAtLeast(0L)

    fun summaryLine(): String = buildString {
        append("E2E[$mode:$label] ")
        append("T6-T0=${firstSentenceLatencyMs?.let { "${it}ms" } ?: "n/a"} ")
        append("T7-T0=${fullTranslationLatencyMs}ms ")
        append("(cap=${captureMs} pre=${preprocessMs} ocr=${ocrMs} grp=${groupingReconstructionMs} ")
        append("prep=${translationPrepMs} tr+ui=${translationAndDisplayMs}ms) ")
        append("regions=$detectedRegions translated=$translatedSentences failed=$failedSentences")
        if (cpuTimeMs >= 0L) append(" cpu=${cpuTimeMs}ms")
        if (memoryUsedKb >= 0L) append(" mem=${memoryUsedKb}KB")
    }
}

/**
 * Thread-safe builder used during one screen pass to stamp T0..T7 monotonically.
 */
class EndToEndRecorder(
    private val label: String,
    private val mode: String,
    private val clockMs: () -> Long,
    private val cpuTimeMsProvider: () -> Long = { -1L },
    private val memoryUsedKbProvider: () -> Long = { -1L }
) {
    private val lock = Any()
    private val cpuStartMs = runCatching { cpuTimeMsProvider() }.getOrDefault(-1L)

    @Volatile var t0Ms: Long = clockMs(); private set
    @Volatile var t1Ms: Long = t0Ms; private set
    @Volatile var t2Ms: Long = t0Ms; private set
    @Volatile var t3Ms: Long = t0Ms; private set
    @Volatile var t4Ms: Long = t0Ms; private set
    @Volatile var t5Ms: Long = t0Ms; private set
    @Volatile var t6Ms: Long? = null; private set
    @Volatile var t7Ms: Long = t0Ms; private set

    private var detectedRegions = 0
    private var translatedSentences = 0
    private var failedSentences = 0
    private var translationFailed = false

    fun overrideT0(explicitT0Ms: Long) = synchronized(lock) {
        if (explicitT0Ms > 0L && explicitT0Ms <= t0Ms) {
            t0Ms = explicitT0Ms
        }
    }

    fun markT1CaptureDone() = synchronized(lock) {
        t1Ms = maxOf(t0Ms, clockMs())
    }

    fun markT2PreprocessDone() = synchronized(lock) {
        t2Ms = maxOf(t1Ms, clockMs())
    }

    fun markT3OcrDone() = synchronized(lock) {
        t3Ms = maxOf(t2Ms, clockMs())
    }

    fun markT4GroupingDone(regions: Int) = synchronized(lock) {
        t4Ms = maxOf(t3Ms, clockMs())
        detectedRegions = regions.coerceAtLeast(0)
    }

    fun markT5TranslationStart() = synchronized(lock) {
        t5Ms = maxOf(t4Ms, clockMs())
    }

    /** Stamped once on the very first complete Arabic sentence displayed (idempotent). */
    fun markT6FirstSentenceDisplayed() = synchronized(lock) {
        if (t6Ms == null) {
            t6Ms = maxOf(t5Ms, clockMs())
        }
    }

    fun markT7AllDisplayed(
        translatedCount: Int,
        failedCount: Int = 0,
        failed: Boolean = false
    ): EndToEndRunRecord = synchronized(lock) {
        val now = clockMs()
        val floor = maxOf(t5Ms, t6Ms ?: t5Ms)
        t7Ms = maxOf(floor, now)
        translatedSentences = translatedCount.coerceAtLeast(0)
        failedSentences = failedCount.coerceAtLeast(0)
        translationFailed = failed
        if (translatedSentences > 0 && t6Ms == null) {
            t6Ms = t7Ms
        }
        val cpuEnd = runCatching { cpuTimeMsProvider() }.getOrDefault(-1L)
        val cpuDelta = if (cpuStartMs >= 0L && cpuEnd >= cpuStartMs) cpuEnd - cpuStartMs else -1L
        val memKb = runCatching { memoryUsedKbProvider() }.getOrDefault(-1L)
        EndToEndRunRecord(
            label = label,
            mode = mode,
            t0Ms = t0Ms,
            t1Ms = t1Ms,
            t2Ms = t2Ms,
            t3Ms = t3Ms,
            t4Ms = t4Ms,
            t5Ms = t5Ms,
            t6Ms = t6Ms,
            t7Ms = t7Ms,
            detectedRegions = detectedRegions,
            translatedSentences = translatedSentences,
            failedSentences = failedSentences,
            translationFailed = translationFailed,
            cpuTimeMs = cpuDelta,
            memoryUsedKb = memKb
        )
    }
}

/**
 * Aggregated metrics across a fixed benchmark dataset or a rolling session window.
 */
data class EndToEndSummary(
    val runs: Int,
    val medianFirstSentenceMs: Long?,
    val p95FirstSentenceMs: Long?,
    val medianFullLatencyMs: Long,
    val p95FullLatencyMs: Long,
    val worstFullLatencyMs: Long,
    val totalProcessingTimeMs: Long,
    val medianCaptureMs: Long,
    val medianPreprocessMs: Long,
    val medianOcrMs: Long,
    val medianGroupingMs: Long,
    val medianTranslationPrepMs: Long,
    val medianTranslationAndDisplayMs: Long,
    val totalDetectedRegions: Int,
    val totalTranslatedSentences: Int,
    val totalFailedSentences: Int,
    val screenFailureRate: Double,
    val sentenceFailureRate: Double,
    val medianCpuTimeMs: Long?,
    val medianMemoryKb: Long?,
    val peakMemoryKb: Long?
) {
    fun formatReport(title: String = "ManhwaLens E2E Pipeline Benchmark"): String = buildString {
        appendLine(title)
        appendLine("Runs: $runs · Total processing time: ${totalProcessingTimeMs} ms")
        appendLine("First Complete Sentence (T6-T0): median=${fmtMs(medianFirstSentenceMs)} · P95=${fmtMs(p95FirstSentenceMs)}")
        appendLine("Full Translation (T7-T0): median=${medianFullLatencyMs}ms · P95=${p95FullLatencyMs}ms · worst=${worstFullLatencyMs}ms")
        appendLine(
            "Stages (median): capture=${medianCaptureMs}ms · preprocess=${medianPreprocessMs}ms · " +
                "ocr=${medianOcrMs}ms · group+reconstruct=${medianGroupingMs}ms · " +
                "prep=${medianTranslationPrepMs}ms · translate+display=${medianTranslationAndDisplayMs}ms"
        )
        appendLine(
            "Dialogue: regions=$totalDetectedRegions · translated=$totalTranslatedSentences · " +
                "failed=$totalFailedSentences · screenFail=${fmtPct(screenFailureRate)} · sentenceFail=${fmtPct(sentenceFailureRate)}"
        )
        appendLine(
            "Resources: cpuMedian=${fmtMs(medianCpuTimeMs)} · memMedian=${fmtKb(medianMemoryKb)} · memPeak=${fmtKb(peakMemoryKb)}"
        )
    }.trim()

    fun compareAgainst(baseline: EndToEndSummary): String = buildString {
        appendLine("Comparison vs baseline:")
        appendDelta("  First-sentence median (T6-T0)", baseline.medianFirstSentenceMs, medianFirstSentenceMs, "ms")
        appendDelta("  First-sentence P95    (T6-T0)", baseline.p95FirstSentenceMs, p95FirstSentenceMs, "ms")
        appendDelta("  Full-screen median    (T7-T0)", baseline.medianFullLatencyMs, medianFullLatencyMs, "ms")
        appendDelta("  Full-screen P95       (T7-T0)", baseline.p95FullLatencyMs, p95FullLatencyMs, "ms")
        appendDelta("  Worst-case            (T7-T0)", baseline.worstFullLatencyMs, worstFullLatencyMs, "ms")
        appendDelta("  Group+Reconstruct median     ", baseline.medianGroupingMs, medianGroupingMs, "ms")
        appendDelta("  Translate+Display median     ", baseline.medianTranslationAndDisplayMs, medianTranslationAndDisplayMs, "ms")
    }.trim()

    private fun StringBuilder.appendDelta(label: String, base: Long?, cur: Long?, unit: String) {
        if (base == null || cur == null) {
            appendLine("$label: n/a")
            return
        }
        val diff = cur - base
        val pct = if (base > 0L) (diff * 100.0) / base else 0.0
        val sign = if (diff > 0) "+" else ""
        appendLine("$label: $base$unit → $cur$unit ($sign$diff$unit / $sign${String.format(Locale.US, "%.1f", pct)}%)")
    }

    private fun fmtMs(v: Long?): String = v?.let { "${it}ms" } ?: "n/a"
    private fun fmtKb(v: Long?): String = v?.let { "${it}KB" } ?: "n/a"
    private fun fmtPct(v: Double): String = String.format(Locale.US, "%.1f%%", v * 100.0)

    companion object {
        fun summarize(records: List<EndToEndRunRecord>): EndToEndSummary {
            if (records.isEmpty()) {
                return EndToEndSummary(
                    runs = 0,
                    medianFirstSentenceMs = null,
                    p95FirstSentenceMs = null,
                    medianFullLatencyMs = 0L,
                    p95FullLatencyMs = 0L,
                    worstFullLatencyMs = 0L,
                    totalProcessingTimeMs = 0L,
                    medianCaptureMs = 0L,
                    medianPreprocessMs = 0L,
                    medianOcrMs = 0L,
                    medianGroupingMs = 0L,
                    medianTranslationPrepMs = 0L,
                    medianTranslationAndDisplayMs = 0L,
                    totalDetectedRegions = 0,
                    totalTranslatedSentences = 0,
                    totalFailedSentences = 0,
                    screenFailureRate = 0.0,
                    sentenceFailureRate = 0.0,
                    medianCpuTimeMs = null,
                    medianMemoryKb = null,
                    peakMemoryKb = null
                )
            }
            val firsts = records.mapNotNull { it.firstSentenceLatencyMs }.sorted()
            val fulls = records.map { it.fullTranslationLatencyMs }.sorted()
            val cpus = records.map { it.cpuTimeMs }.filter { it >= 0L }.sorted()
            val mems = records.map { it.memoryUsedKb }.filter { it >= 0L }.sorted()
            val totalRegions = records.sumOf { it.detectedRegions }
            val totalTranslated = records.sumOf { it.translatedSentences }
            val totalFailed = records.sumOf { it.failedSentences }
            val failedScreens = records.count { it.translationFailed }
            return EndToEndSummary(
                runs = records.size,
                medianFirstSentenceMs = median(firsts),
                p95FirstSentenceMs = percentile(firsts, 95),
                medianFullLatencyMs = median(fulls) ?: 0L,
                p95FullLatencyMs = percentile(fulls, 95) ?: 0L,
                worstFullLatencyMs = fulls.last(),
                totalProcessingTimeMs = fulls.sum(),
                medianCaptureMs = median(records.map { it.captureMs }.sorted()) ?: 0L,
                medianPreprocessMs = median(records.map { it.preprocessMs }.sorted()) ?: 0L,
                medianOcrMs = median(records.map { it.ocrMs }.sorted()) ?: 0L,
                medianGroupingMs = median(records.map { it.groupingReconstructionMs }.sorted()) ?: 0L,
                medianTranslationPrepMs = median(records.map { it.translationPrepMs }.sorted()) ?: 0L,
                medianTranslationAndDisplayMs = median(records.map { it.translationAndDisplayMs }.sorted()) ?: 0L,
                totalDetectedRegions = totalRegions,
                totalTranslatedSentences = totalTranslated,
                totalFailedSentences = totalFailed,
                screenFailureRate = failedScreens.toDouble() / records.size,
                sentenceFailureRate = if (totalRegions > 0) totalFailed.toDouble() / totalRegions else 0.0,
                medianCpuTimeMs = median(cpus),
                medianMemoryKb = median(mems),
                peakMemoryKb = mems.lastOrNull()
            )
        }

        fun median(sorted: List<Long>): Long? {
            if (sorted.isEmpty()) return null
            val m = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[m] else (sorted[m - 1] + sorted[m]) / 2L
        }

        fun percentile(sorted: List<Long>, p: Int): Long? {
            if (sorted.isEmpty()) return null
            val idx = ceil((p / 100.0) * sorted.size).toInt().coerceIn(1, sorted.size) - 1
            return sorted[idx]
        }
    }
}

/**
 * Rolling in-memory store of recent live/tap E2E runs so Diagnostics & Copy Report
 * expose real user-perceived T6-T0 / T7-T0 latency statistics.
 */
object EndToEndMetricsStore {
    private const val MAX_RECORDS = 100
    private val lock = Any()
    private val records = ArrayDeque<EndToEndRunRecord>(MAX_RECORDS)

    fun record(entry: EndToEndRunRecord) = synchronized(lock) {
        if (records.size >= MAX_RECORDS) records.removeFirst()
        records.addLast(entry)
    }

    fun snapshot(): List<EndToEndRunRecord> = synchronized(lock) { records.toList() }

    fun last(): EndToEndRunRecord? = synchronized(lock) { records.lastOrNull() }

    fun summarize(): EndToEndSummary = EndToEndSummary.summarize(snapshot())

    fun clear() = synchronized(lock) { records.clear() }
}
