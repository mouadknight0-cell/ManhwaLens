package com.manhwalens.app.bench

import com.manhwalens.app.translate.ArabicValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Automated End-to-End Pipeline Benchmark Suite (T0..T7) across the fixed 10-screen dataset.
 *
 * Compares:
 *  - Baseline batch mode (no progressive emission, cache cleared every round)
 *  - Optimized progressive + cached mode (priority block ordering, progressive painting, LRU hits)
 */
class EndToEndBenchmarkTest {

    @Test
    fun fixedDataset_allTenScreensExecuteCleanly_andReportMetrics() {
        assertEquals(10, EndToEndBenchmarkRunner.FIXED_DATASET.size)

        val baseline = EndToEndBenchmarkRunner.run(
            EndToEndBenchmarkRunner.BenchmarkConfig(
                rounds = 2,
                simulatedPerBlockTranslateMs = 12L,
                simulatedBatchTranslateMs = 10L,
                simulateSleep = true,
                progressiveDisplay = false,
                clearCacheEachRound = true
            )
        )

        val optimized = EndToEndBenchmarkRunner.run(
            EndToEndBenchmarkRunner.BenchmarkConfig(
                rounds = 2,
                simulatedPerBlockTranslateMs = 12L,
                simulatedBatchTranslateMs = 10L,
                simulateSleep = true,
                progressiveDisplay = true,
                clearCacheEachRound = false
            )
        )

        println(baseline.summary.formatReport("=== BASELINE (Batch, Cold Cache) ==="))
        println()
        println(optimized.summary.formatReport("=== OPTIMIZED (Progressive + Warm Cache) ==="))
        println()
        println(optimized.summary.compareAgainst(baseline.summary))

        assertEquals(20, baseline.records.size)
        assertEquals(20, optimized.records.size)
        assertEquals("Screen failure rate must be 0%", 0.0, optimized.summary.screenFailureRate, 0.0001)
        assertEquals("Sentence failure rate must be 0%", 0.0, optimized.summary.sentenceFailureRate, 0.0001)

        // Verify T0..T7 monotonic ordering on every run record
        for (rec in optimized.records) {
            assertTrue("T1 >= T0", rec.t1Ms >= rec.t0Ms)
            assertTrue("T2 >= T1", rec.t2Ms >= rec.t1Ms)
            assertTrue("T3 >= T2", rec.t3Ms >= rec.t2Ms)
            assertTrue("T4 >= T3", rec.t4Ms >= rec.t3Ms)
            assertTrue("T5 >= T4", rec.t5Ms >= rec.t4Ms)
            assertTrue("T7 >= T5", rec.t7Ms >= rec.t5Ms)
            if (rec.translatedSentences > 0) {
                val t6 = rec.t6Ms
                assertNotNull("Expected T6 when sentences displayed (${rec.label})", t6)
                assertTrue("T6 >= T5", t6!! >= rec.t5Ms)
                assertTrue("T7 >= T6", rec.t7Ms >= t6)
            }
        }

        // Progressive + warm cache must beat batch + cold cache on both T6-T0 and T7-T0
        val optFirst = optimized.summary.medianFirstSentenceMs!!
        val baseFirst = baseline.summary.medianFirstSentenceMs!!
        assertTrue(
            "Expected progressive+cached first-sentence median ($optFirst ms) < baseline ($baseFirst ms)",
            optFirst < baseFirst
        )
        assertTrue(
            "Expected optimized full-screen median (${optimized.summary.medianFullLatencyMs} ms) < baseline (${baseline.summary.medianFullLatencyMs} ms)",
            optimized.summary.medianFullLatencyMs < baseline.summary.medianFullLatencyMs
        )

        // Every displayed sentence in non-empty screens must pass ArabicValidator
        for ((screenId, texts) in optimized.displayedTextsByScreen) {
            if (screenId == "08_empty_panel") {
                assertTrue("Empty panel should display 0 lines", texts.isEmpty())
            } else {
                assertTrue("Screen $screenId should display >= 1 line", texts.isNotEmpty())
                for (ar in texts) {
                    assertTrue("Displayed text for $screenId must contain Arabic: $ar", ArabicValidator.hasArabic(ar))
                }
            }
        }
    }

    @Test
    fun pureComputeBenchmark_noSleeps_fastAndDeterministic() {
        val result = EndToEndBenchmarkRunner.run(
            EndToEndBenchmarkRunner.BenchmarkConfig(
                rounds = 3,
                simulateSleep = false,
                progressiveDisplay = true,
                clearCacheEachRound = false
            )
        )
        assertEquals(30, result.records.size)
        assertEquals(0, result.summary.totalFailedSentences)
        assertTrue("Expected positive detected regions", result.summary.totalDetectedRegions > 0)
        assertEquals(result.summary.totalDetectedRegions, result.summary.totalTranslatedSentences)
        println(result.summary.formatReport("=== PURE COMPUTE (No Simulated Sleeps, 30 Runs) ==="))
    }
}
