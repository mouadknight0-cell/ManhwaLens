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
        val batchRecoveryCount: Int,
        val cases: List<CaseResult>,
        val failureDetails: List<String> = emptyList()
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

    data class Report(
        val totalCases: Int,
        val mlKit: EngineReport,
        val bergamot: EngineReport,
        val mlKitDispatchDescription: String
    ) {
        fun toText(): String = buildString {
            appendLine("Offline engine comparison (same ${totalCases}-case EN→AR corpus)")
            appendLine("Identical preprocessing: glossary + single-line normalization · no app translation cache")
            appendLine("Raw-engine comparison: cross-engine quality fallback is disabled; each engine is evaluated on its own outputs.")
            appendLine("ML Kit page dispatch: $mlKitDispatchDescription")
            appendLine("Bergamot page dispatch: one native multi-input request per pass for this fixed corpus")
            appendLine("Passes: ${MEASURED_PASSES} per engine; first pass reported separately (Bergamot may include native model loading; ML Kit model is prechecked), next ${MEASURED_PASSES - 1} are warm")
            appendLine("Paired execution order alternates each pass to reduce systematic thermal/order bias.")
            appendLine("Timing is page-batch wall time using each engine's dispatch path; it is not isolated single-sentence model latency.")
            appendLine("Quality counts and outputs below are from the final pass; human semantic review is required.")
            appendLine()
            appendEngine(mlKit)
            appendLine()
            appendEngine(bergamot)
            appendLine()
            appendLine("Token F1 measures lexical overlap only; it is not semantic accuracy.")
            appendLine("A failed batch is retried one case at a time to isolate failures.")
        }.trim()
    }

    data class SingleReport(
        val totalCases: Int,
        val engine: EngineReport,
        val dispatchDescription: String
    ) {
        fun toText(): String = buildString {
            appendLine("Offline engine benchmark — ${engine.engine} only (same ${totalCases}-case EN→AR corpus)")
            appendLine("Preprocessing: glossary + single-line normalization · no app translation cache")
            appendLine("Dispatch: $dispatchDescription")
            appendLine("Passes: ${MEASURED_PASSES}; first pass reported separately and may include native service/model initialization; next ${MEASURED_PASSES - 1} are warm")
            appendLine("No ML Kit invocation or ML Kit fallback is part of this benchmark.")
            appendLine("Quality results and per-case outputs are from the final pass; human semantic review is required.")
            appendLine()
            appendEngine(engine)
            appendLine()
            appendLine("Token F1 measures lexical overlap only; it is not semantic accuracy.")
            appendLine("A failed batch is retried one case at a time to isolate failures.")
        }.trim()
    }

    private data class RunResult(
        val outputs: List<String>,
        val firstPassMs: Long,
        val warmMedianMs: Long,
        val warmWorstMs: Long,
        val recoveries: Int,
        val failureDetails: List<String>
    )

    /**
     * A true one-engine benchmark: the supplied engine callback is the only engine invoked.
     * The first pass includes adapter/model startup when the caller creates a fresh adapter;
     * subsequent passes measure the warmed adapter. No runtime engine or batching preference
     * is read or changed here.
     */
    fun runSingleEngine(
        engineName: String,
        translateBatch: (List<String>) -> List<String>,
        cases: List<TranslationQualityBenchmark.Case> = TranslationQualityBenchmark.CASES,
        clock: () -> Long = { System.nanoTime() / 1_000_000L },
        dispatchDescription: String = "one raw multi-input batch per pass"
    ): SingleReport {
        require(engineName.isNotBlank()) { "Engine name must not be blank" }
        require(cases.map { it.id }.distinct().size == cases.size) { "Case IDs must be unique" }
        val inputs = cases.map { BergamotTextPreparation.asSingleLine(Glossary.prepare(it.source)) }
        val times = ArrayList<Long>(MEASURED_PASSES)
        var output = List(inputs.size) { "" }
        var recoveries = 0
        val passFailureDetails = linkedSetOf<String>()
        repeat(MEASURED_PASSES) {
            val pass = runPass(inputs, translateBatch, clock)
            output = pass.output
            times += pass.elapsedMs
            if (pass.recovered) recoveries++
            pass.failureDetails.forEach { passFailureDetails += it }
        }
        val warm = times.drop(1).sorted()
        val median = if (warm.isEmpty()) 0L else if (warm.size % 2 == 1) warm[warm.size / 2]
        else (warm[warm.size / 2 - 1] + warm[warm.size / 2]) / 2
        val summary = RunResult(
            outputs = output,
            firstPassMs = times.firstOrNull() ?: 0L,
            warmMedianMs = median,
            warmWorstMs = warm.lastOrNull() ?: 0L,
            recoveries = recoveries,
            failureDetails = passFailureDetails.toList()
        )
        return SingleReport(cases.size, score(engineName, cases, summary), dispatchDescription)
    }

    /** Raw engine callbacks only. A failed/malformed batch is retried per case. */
    fun run(
        translateMlKitBatch: (List<String>) -> List<String>,
        translateBergamotBatch: (List<String>) -> List<String>,
        cases: List<TranslationQualityBenchmark.Case> = TranslationQualityBenchmark.CASES,
        clock: () -> Long = { System.nanoTime() / 1_000_000L },
        mlKitDispatchDescription: String = "caller-defined raw requests"
    ): Report {
        require(cases.map { it.id }.distinct().size == cases.size) { "Case IDs must be unique" }
        val inputs = cases.map { BergamotTextPreparation.asSingleLine(Glossary.prepare(it.source)) }
        val mlTimes = ArrayList<Long>(MEASURED_PASSES)
        val bergamotTimes = ArrayList<Long>(MEASURED_PASSES)
        var mlOutput = List(inputs.size) { "" }
        var bergamotOutput = List(inputs.size) { "" }
        var mlRecoveries = 0
        var bergamotRecoveries = 0
        val mlFailures = linkedSetOf<String>()
        val bergamotFailures = linkedSetOf<String>()

        // Run paired passes and alternate which engine goes first. This reduces systematic
        // thermal/order bias compared with running all ML Kit passes before all Bergamot passes.
        repeat(MEASURED_PASSES) { pass ->
            fun runMlKit() {
                val result = runPass(inputs, translateMlKitBatch, clock)
                mlOutput = result.output
                mlTimes += result.elapsedMs
                if (result.recovered) mlRecoveries++
                result.failureDetails.forEach { mlFailures += it }
            }
            fun runBergamot() {
                val result = runPass(inputs, translateBergamotBatch, clock)
                bergamotOutput = result.output
                bergamotTimes += result.elapsedMs
                if (result.recovered) bergamotRecoveries++
                result.failureDetails.forEach { bergamotFailures += it }
            }
            if (pass % 2 == 0) {
                runMlKit()
                runBergamot()
            } else {
                runBergamot()
                runMlKit()
            }
        }

        fun summarize(output: List<String>, times: List<Long>, recoveries: Int, failures: Set<String>): RunResult {
            val warm = times.drop(1).sorted()
            val median = if (warm.isEmpty()) 0L else if (warm.size % 2 == 1) warm[warm.size / 2]
            else (warm[warm.size / 2 - 1] + warm[warm.size / 2]) / 2
            return RunResult(output, times.firstOrNull() ?: 0L, median, warm.lastOrNull() ?: 0L, recoveries, failures.toList())
        }
        return Report(
            cases.size,
            score("ML Kit", cases, summarize(mlOutput, mlTimes, mlRecoveries, mlFailures)),
            score("Bergamot", cases, summarize(bergamotOutput, bergamotTimes, bergamotRecoveries, bergamotFailures)),
            mlKitDispatchDescription
        )
    }

    private data class PassResult(
        val output: List<String>,
        val elapsedMs: Long,
        val recovered: Boolean,
        val failureDetails: List<String>
    )

    /** Run one batch; if it fails or returns a malformed count, isolate retries by case. */
    private fun runPass(
        inputs: List<String>,
        translate: (List<String>) -> List<String>,
        clock: () -> Long
    ): PassResult {
        val start = clock()
        var recovered = false
        val failures = linkedSetOf<String>()
        val output = try {
            translate(inputs).also {
                check(it.size == inputs.size) {
                    "Engine output count mismatch: expected ${inputs.size}, received ${it.size}"
                }
                val blankIndexes = it.indices.filter { index -> it[index].isBlank() }
                check(blankIndexes.isEmpty()) {
                    "Engine returned blank output(s) at index(es): ${blankIndexes.joinToString()}"
                }
            }
        } catch (e: Exception) {
            recovered = true
            failures += "batch: ${brief(e)}"
            inputs.mapIndexed { index, input ->
                try {
                    translate(listOf(input)).singleOrNull().orEmpty().also { value ->
                        if (value.isBlank()) failures += "case ${index + 1}: empty native output"
                    }
                } catch (singleError: Exception) {
                    failures += "case ${index + 1}: ${brief(singleError)}"
                    ""
                }
            }
        }
        return PassResult(output, (clock() - start).coerceAtLeast(0L), recovered, failures.toList())
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
        return EngineReport(name, run.firstPassMs, run.warmMedianMs, run.warmWorstMs, run.recoveries, results, run.failureDetails)
    }

    private fun brief(error: Throwable): String {
        val root = error.cause ?: error
        return "${root.javaClass.simpleName}: ${root.message.orEmpty()}".replace(Regex("[\\r\\n]+"), " ").take(220)
    }

    private fun tokens(value: String): List<String> = normalize(value).split(Regex("\\s+")).filter { it.isNotBlank() }
    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("[\\u064B-\\u065F\\u0670\\u0640]"), "").replace(Regex("[أإآٱ]"), "ا").replace('ى', 'ي')
        .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().replace(Regex("\\s+"), " ")

    private fun StringBuilder.appendEngine(r: EngineReport) {
        appendLine("${r.engine}: accepted ${r.accepted}/${r.cases.size}, rejected ${r.rejected}, blank ${r.blanks}, English echo/fragment ${r.englishEchoes}")
        appendLine("  exact ${r.exactMatches}/${r.cases.size} · mean token F1 ${ "%.1f".format(Locale.ROOT, r.meanTokenF1 * 100) }% · batch recoveries ${r.batchRecoveryCount}")
        appendLine("  first pass ${r.firstPassElapsedMs}ms · warm median ${r.warmMedianMs}ms · warm worst ${r.warmWorstMs}ms")
        r.failureDetails.take(8).forEach { appendLine("  native failure: ${it.take(220)}") }
        r.cases.forEach { c ->
            appendLine("  #${c.id} [${c.category}] ${if (c.accepted) "OK" else c.issue}")
            appendLine("    EN: ${c.source}")
            appendLine("    REF: ${c.referenceArabic}")
            appendLine("    OUT: ${c.output.ifBlank { "<empty>" }}")
        }
    }
}