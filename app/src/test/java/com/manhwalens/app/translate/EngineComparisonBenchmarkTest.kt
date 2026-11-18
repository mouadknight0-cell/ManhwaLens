package com.manhwalens.app.translate

import com.manhwalens.app.translate.bergamot.BergamotTextPreparation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineComparisonBenchmarkTest {
    @Test
    fun singleEngineBenchmarkInvokesOnlyTheSuppliedEngineForEveryPass() {
        var calls = 0
        val seen = mutableListOf<List<String>>()
        val report = EngineComparisonBenchmark.runSingleEngine(
            engineName = "Bergamot",
            translateBatch = { inputs ->
                calls++
                seen += inputs
                inputs.map { input ->
                    TranslationQualityBenchmark.CASES.first {
                        BergamotTextPreparation.asSingleLine(Glossary.prepare(it.source)) == input
                    }.referenceArabic
                }
            },
            dispatchDescription = "Bergamot native batch only"
        )

        assertEquals(EngineComparisonBenchmark.MEASURED_PASSES, calls)
        assertEquals(EngineComparisonBenchmark.MEASURED_PASSES, seen.size)
        assertTrue(seen.all { it == seen.first() })
        assertEquals("Bergamot", report.engine.engine)
        assertEquals(23, report.totalCases)
        assertEquals(23, report.engine.accepted)
        assertEquals(23, report.engine.exactMatches)
        assertTrue(report.toText().contains("No ML Kit invocation"))
    }

    @Test
    fun singleEngineBenchmarkRecoversFailedBatchPerCase() {
        var calls = 0
        val report = EngineComparisonBenchmark.runSingleEngine(
            engineName = "Bergamot",
            translateBatch = { inputs ->
                calls++
                if (inputs.size > 1) error("synthetic native batch failure")
                listOf("ترجمة صحيحة")
            },
            cases = TranslationQualityBenchmark.CASES.take(2)
        )

        assertEquals(EngineComparisonBenchmark.MEASURED_PASSES * 3, calls)
        assertEquals(EngineComparisonBenchmark.MEASURED_PASSES, report.engine.batchRecoveryCount)
        assertEquals(2, report.engine.cases.size)
        assertTrue(report.toText().contains("synthetic native batch failure"))
    }

    @Test
    fun bothEnginesReceiveIdenticalPreparedCorpusAndStableIds() {
        val seenMl = mutableListOf<List<String>>()
        val seenBergamot = mutableListOf<List<String>>()
        val exact = { inputs: List<String> ->
            inputs.map { input ->
                TranslationQualityBenchmark.CASES.first {
                BergamotTextPreparation.asSingleLine(Glossary.prepare(it.source)) == input
            }.referenceArabic
            }
        }
        val report = EngineComparisonBenchmark.run(
            translateMlKitBatch = { seenMl += it; exact(it) },
            translateBergamotBatch = { seenBergamot += it; exact(it) }
        )

        assertEquals(23, report.totalCases)
        assertEquals(EngineComparisonBenchmark.MEASURED_PASSES, seenMl.size)
        assertEquals(EngineComparisonBenchmark.MEASURED_PASSES, seenBergamot.size)
        assertEquals(seenMl, seenBergamot)
        assertEquals((1..23).toList(), report.mlKit.cases.map { it.id })
        assertEquals((1..23).toList(), report.bergamot.cases.map { it.id })
        assertEquals(23, report.mlKit.accepted)
        assertEquals(23, report.bergamot.accepted)
        assertEquals(23, report.mlKit.exactMatches)
        assertEquals(23, report.bergamot.exactMatches)
    }

    @Test
    fun failedBatchIsRetriedPerCaseAndFailureDoesNotEraseOtherOutputs() {
        val report = EngineComparisonBenchmark.run(
            translateMlKitBatch = { inputs ->
                if (inputs.size > 1) error("synthetic batch failure")
                listOf("ترجمة صحيحة")
            },
            translateBergamotBatch = { inputs -> inputs.map { "ترجمة صحيحة" } },
            cases = TranslationQualityBenchmark.CASES.take(3)
        )

        assertEquals(EngineComparisonBenchmark.MEASURED_PASSES, report.mlKit.batchRecoveryCount)
        assertTrue(report.mlKit.cases.all { it.output == "ترجمة صحيحة" })
        assertEquals(0, report.bergamot.batchRecoveryCount)
    }

    @Test
    fun malformedBatchCountAlsoTriggersIsolatedRetries() {
        val report = EngineComparisonBenchmark.run(
            translateMlKitBatch = { inputs -> if (inputs.size > 1) emptyList() else listOf("نعم") },
            translateBergamotBatch = { inputs -> inputs.map { "نعم" } },
            cases = TranslationQualityBenchmark.CASES.take(2)
        )
        assertEquals(EngineComparisonBenchmark.MEASURED_PASSES, report.mlKit.batchRecoveryCount)
        assertEquals(listOf("نعم", "نعم"), report.mlKit.cases.map { it.output })
    }

    @Test
    fun emptyPerCaseNativeResultsAreReportedAsFailures() {
        val report = EngineComparisonBenchmark.runSingleEngine(
            engineName = "Bergamot",
            translateBatch = { List(it.size) { "" } },
            cases = TranslationQualityBenchmark.CASES.take(2)
        )
        assertEquals(2, report.engine.blanks)
        assertTrue(report.toText().contains("empty native output"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateCaseIdsAreRejected() {
        val duplicate = TranslationQualityBenchmark.CASES.take(1) + TranslationQualityBenchmark.CASES.take(1)
        EngineComparisonBenchmark.run({ it.map { "نعم" } }, { it.map { "نعم" } }, duplicate)
    }

    @Test
    fun engineExecutionOrderAlternatesAcrossPairedPasses() {
        val order = mutableListOf<String>()
        EngineComparisonBenchmark.run(
            translateMlKitBatch = { inputs -> order += "ML"; inputs.map { "ترجمة" } },
            translateBergamotBatch = { inputs -> order += "Bergamot"; inputs.map { "ترجمة" } },
            cases = TranslationQualityBenchmark.CASES.take(1)
        )
        assertEquals(
            listOf("ML", "Bergamot", "Bergamot", "ML", "ML", "Bergamot", "Bergamot", "ML"),
            order
        )
    }
}
