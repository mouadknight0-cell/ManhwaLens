package com.manhwalens.app.translate

import com.manhwalens.app.translate.bergamot.BergamotTextPreparation
import java.text.Normalizer
import java.util.Locale

/** Same-corpus, cache-free diagnostic. It cannot read or mutate runtime preferences. */
object EngineComparisonBenchmark {
    const val MEASURED_PASSES = 4

    data class CaseResult(
        val id: Int, val category: String, val source: String, val referenceArabic: String,
        val output: String, val accepted: Boolean, val issue: ArabicValidator.Issue?,
        val exactMatch: Boolean, val tokenF1: Double
    )

    data class EngineReport(
        val engine: String, val firstPassElapsedMs: Long, val warmMedianMs: Long, val warmWorstMs: Long,
        val batchRecoveryCount: Int, val cases: List<CaseResult>
    ) {
        val accepted: Int get() = cases.count { it.accepted }
        val rejected: Int get() = cases.size - accepted
        val blanks: Int get() = cases.count { it.output.isBlank() }
        val englishEchoes: Int get() = cases.count {
            it.issue == ArabicValidator.Issue.UNTRANSLATED_ECHO ||
                it.issue == ArabicValidator.Issue.PARTIAL_TRANSLATION ||
                it.issue == ArabicValidator.Issue.UNTRANSLATED_FRAGMENT
        }
        val exactMatches: Int get() = cases.count { it.exactMatch }
        val meanTokenF1: Double get() = if (cases.isEmpty()) 0.0 else cases.map { it.tokenF1 }.average()
    }

    data class Report(val totalCases: Int, val mlKit: EngineReport, val bergamot: EngineReport) {
        fun toText(): String = buildString {
            appendLine("Offline engine comparison (same ${totalCases}-case EN→AR corpus)")
            appendLine("Identical preprocessing: glossary + single-line normalization · no app translation cache")
            appendLine("Passes: ${MEASURED_PASSES} per engine; first pass reported separately (Bergamot may include native model loading; ML Kit model is prechecked), next ${MEASURED_PASSES - 1} are warm")
            appendLine("Device diagnostic only; human semantic review is required.")
            appendLine()
            appendEngine(mlKit)
            appendLine()
            appendEngine(bergamot)
            appendLine()
            appendLine("Token F1 measures lexical overlap only; it is not semantic accuracy.")
            appendLine("A failed batch is retried one case at a time to isolate failures.")
        }.trim()
    }

    private data class RunResult(val outputs: List<String>, val firstPassMs: Long, val warmMedianMs: Long, val warmWorstMs: Long, val recoveries: Int)

    /** Raw engine callbacks only. A failed/malformed batch is retried per case. */
    fun run(
        translateMlKitBatch: (List<String>) -> List<String>,
        translateBergamotBatch: (List<String>) -> List<String>,
        cases: List<TranslationQualityBenchmark.Case> = TranslationQualityBenchmark.CASES,
        clock: () -> Long = { System.nanoTime() / 1_000_000L }
    ): Report {
        require(cases.map { it.id }.distinct().size == cases.size) { "Case IDs must be unique" }
        val inputs = cases.map { BergamotTextPreparation.asSingleLine(Glossary.prepare(it.source)) }
        val ml = runEngine(inputs, translateMlKitBatch, clock)
        val bergamot = runEngine(inputs, translateBergamotBatch, clock)
        return Report(cases.size, score("ML Kit", cases, ml), score("Bergamot", cases, bergamot))
    }

    private fun runEngine(inputs: List<String>, translate: (List<String>) -> List<String>, clock: () -> Long): RunResult {
        val times = ArrayList<Long>(MEASURED_PASSES)
        var output = List(inputs.size) { "" }
        var recoveries = 0
        repeat(MEASURED_PASSES) {
            val start = clock()
            output = try {
                translate(inputs).also { check(it.size == inputs.size) { "Engine output count mismatch" } }
            } catch (_: Exception) {
                recoveries++
                inputs.map { input -> runCatching { translate(listOf(input)).singleOrNull().orEmpty() }.getOrDefault("") }
            }
            times += (clock() - start).coerceAtLeast(0L)
        }
        val warm = times.drop(1).sorted()
        val median = if (warm.isEmpty()) 0L else if (warm.size % 2 == 1) warm[warm.size / 2] else (warm[warm.size / 2 - 1] + warm[warm.size / 2]) / 2
        return RunResult(output, times.firstOrNull() ?: 0L, median, warm.lastOrNull() ?: 0L, recoveries)
    }

    private fun score(name: String, cases: List<TranslationQualityBenchmark.Case>, run: RunResult): EngineReport {
        val results = cases.mapIndexed { index, case ->
            val output = run.outputs.getOrNull(index).orEmpty()
            val issue = ArabicValidator.issue(case.source, output)
            val expected = tokens(case.referenceArabic)
            val actual = tokens(output)
            val counts = expected.groupingBy { it }.eachCount().toMutableMap()
            var common = 0
            actual.forEach { token -> val n = counts[token] ?: 0; if (n > 0) { common++; counts[token] = n - 1 } }
            val precision = if (actual.isEmpty()) 0.0 else common.toDouble() / actual.size
            val recall = if (expected.isEmpty()) 0.0 else common.toDouble() / expected.size
            val f1 = if (precision + recall == 0.0) 0.0 else 2.0 * precision * recall / (precision + recall)
            CaseResult(case.id, case.category, case.source, case.referenceArabic, output, issue == null, issue, normalize(output) == normalize(case.referenceArabic), f1)
        }
        return EngineReport(name, run.firstPassMs, run.warmMedianMs, run.warmWorstMs, run.recoveries, results)
    }

    private fun tokens(value: String): List<String> = normalize(value).split(Regex("\\s+")).filter { it.isNotBlank() }
    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "").replace(Regex("[أإآٱ]"), "ا").replace('ى', 'ي')
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

    private fun StringBuilder.appendEngine(r: EngineReport) {
        appendLine("${r.engine}: accepted ${r.accepted}/${r.cases.size}, rejected ${r.rejected}, blank ${r.blanks}, English echo/fragment ${r.englishEchoes}")
        appendLine("  exact ${r.exactMatches}/${r.cases.size} · mean token F1 ${ "%.1f".format(Locale.ROOT, r.meanTokenF1 * 100) }% · batch recoveries ${r.batchRecoveryCount}")
        appendLine("  first pass ${r.firstPassElapsedMs}ms · warm median ${r.warmMedianMs}ms · warm worst ${r.warmWorstMs}ms")
        r.cases.forEach { c ->
            appendLine("  #${c.id} [${c.category}] ${if (c.accepted) "OK" else c.issue}")
            appendLine("    EN: ${c.source}")
            appendLine("    REF: ${c.referenceArabic}")
            appendLine("    OUT: ${c.output.ifBlank { "<empty>" }}")
        }
    }
}