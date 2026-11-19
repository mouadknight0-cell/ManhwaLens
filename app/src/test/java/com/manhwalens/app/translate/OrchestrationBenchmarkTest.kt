package com.manhwalens.app.translate

import com.manhwalens.app.ocr.BubbleGrouper
import com.manhwalens.app.ocr.DetectedLine
import com.manhwalens.app.ocr.geometry.Rect2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * v0.13.0 — fixed-dataset ORCHESTRATION benchmark (issue #15).
 *
 * The dataset is fixed: [BenchmarkRunner.SAMPLE] (20 bubbles) plus OCR lines derived
 * from it deterministically. Every scenario runs the REAL orchestration pieces
 * (BubbleGrouper → PagePlan → CachingTranslator) with a scripted-latency engine, so
 * the numbers are comparable across runs and across optimizations. Timings are printed
 * to stdout (visible in the CI test report) and correctness invariants are asserted:
 * a benchmark that translates the wrong thing is worthless.
 *
 * What this does NOT measure: on-device ML Kit / network latency, bitmap capture,
 * overlay rasterization. Those need a device — see PERFORMANCE.md (honest limits).
 */
class OrchestrationBenchmarkTest {

    /** Engine with a fixed per-block latency; Arabic output via [FakeArabicEngine]. */
    private class ScriptedLatencyTranslator(
        private val latencyMsPerBlock: Long,
        override val engineId: String = "scripted"
    ) : Translator {
        var calls = 0
        var blocksReceived = 0
        override fun translatePage(blocks: List<TranslationBlock>): PageTranslation {
            calls++
            blocksReceived += blocks.size
            if (latencyMsPerBlock > 0) Thread.sleep(latencyMsPerBlock * blocks.size)
            return PageTranslation.Success(
                blocks = blocks.map { TranslatedBlock(it.id, it.english, FakeArabicEngine.translate(it.english)) },
                engineId = engineId,
                elapsedMs = latencyMsPerBlock * blocks.size,
                cacheHits = 0,
                cacheMisses = blocks.size
            )
        }
    }

    /** 40 OCR lines: two per sample bubble, stacked vertically (deterministic). */
    private fun sampleLines(): List<DetectedLine> {
        val lines = ArrayList<DetectedLine>(40)
        for ((i, b) in BenchmarkRunner.SAMPLE.withIndex()) {
            val words = b.english.split(' ')
            val half = (words.size / 2).coerceAtLeast(1)
            // 200px pitch: intra-bubble gap 5px (merges), inter-bubble gap 115px
            // (BubbleGrouper merges at most 0.9 x line height = 36px) — no cross-merge.
            val top = i * 200f
            lines += DetectedLine(words.take(half).joinToString(" "), Rect2D(10f, top, 700f, top + 40f))
            lines += DetectedLine(words.drop(half).joinToString(" "), Rect2D(10f, top + 45f, 700f, top + 85f))
        }
        return lines
    }

    private fun medianOf(values: List<Long>): Long {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private fun bench(name: String, passes: Int = 5, block: () -> Unit): Long {
        repeat(2) { block() } // warm-up (JIT, caches)
        val times = ArrayList<Long>(passes)
        repeat(passes) {
            val t0 = System.nanoTime()
            block()
            times += (System.nanoTime() - t0) / 1_000_000
        }
        val median = medianOf(times)
        println("ORCH-BENCH $name: median=${median}ms samples=${times}")
        return median
    }

    @Test
    fun groupingProducesTwentyBubbles() {
        val lines = sampleLines()
        val blocks = BubbleGrouper.group(lines)
        assertEquals(20, blocks.size)
        bench("grouping(40 lines -> 20 bubbles)") { BubbleGrouper.group(lines) }
    }

    @Test
    fun pagePlanKeepsAllSampleBubbles() {
        val blocks = BenchmarkRunner.SAMPLE
        val plan = PagePlan.prepare(blocks)
        // "KRAK" is a provable SFX burst — PagePlan drops it before the engine.
        assertEquals(19, plan.uniqueCount)
        assertEquals(1, plan.droppedCount)
        bench("pagePlan(20 blocks)") { PagePlan.prepare(blocks) }
    }

    @Test
    fun coldTranslateThenWarmTranslateServesAllFromCache() {
        val engine = ScriptedLatencyTranslator(latencyMsPerBlock = 2)
        val caching = CachingTranslator(engine, TranslationCache())
        val blocks = BenchmarkRunner.SAMPLE

        val cold = caching.translatePage(blocks)
        assertTrue(cold is PageTranslation.Success)
        cold as PageTranslation.Success
        assertEquals(20, cold.blocks.count { it.arabic.isNotBlank() })
        assertEquals(1, engine.calls)
        assertEquals(20, engine.blocksReceived)

        val warm = caching.translatePage(blocks)
        assertTrue(warm is PageTranslation.Success)
        warm as PageTranslation.Success
        assertEquals(20, warm.cacheHits)
        assertEquals(0, warm.cacheMisses)
        assertEquals(1, engine.calls) // warm pass never touches the engine

        bench("translate cold (20 blocks, 2ms/block engine)") {
            CachingTranslator(ScriptedLatencyTranslator(2), TranslationCache()).translatePage(blocks)
        }
        bench("translate warm (20 blocks, all cached)") { caching.translatePage(blocks) }
    }

    @Test
    fun dedupeFanoutSendsUniqueTextsOnce() {
        // 20 blocks, only 5 unique texts (each repeated 4x) — the engine must see 5.
        // (KRAK excluded: PagePlan would drop it as SFX before dedupe.)
        val unique = BenchmarkRunner.SAMPLE.filter { it.english != "KRAK" }.take(5)
        val blocks = (0 until 20).map { i ->
            val src = unique[i % unique.size]
            TranslationBlock(id = i + 1, english = src.english)
        }
        val plan = PagePlan.prepare(blocks)
        assertEquals(5, plan.uniqueCount)

        val engine = ScriptedLatencyTranslator(latencyMsPerBlock = 2)
        val caching = CachingTranslator(engine, TranslationCache())
        val engineBlocks = plan.items.map { TranslationBlock(it.ids.first(), it.text) }
        val result = caching.translatePage(engineBlocks)
        assertTrue(result is PageTranslation.Success)
        assertEquals(5, engine.blocksReceived)

        val expanded = PagePlan.expand(plan, (result as PageTranslation.Success).blocks, blocks.associate { it.id to it.english })
        assertEquals(20, expanded.size)
        assertTrue(expanded.all { it.arabic.isNotBlank() })
        bench("dedupe fanout (20 blocks -> 5 engine calls)") {
            val e = ScriptedLatencyTranslator(2)
            val c = CachingTranslator(e, TranslationCache())
            val r = c.translatePage(engineBlocks)
            PagePlan.expand(plan, (r as PageTranslation.Success).blocks, blocks.associate { it.id to it.english })
        }
    }

    @Test
    fun parallelDispatchBeatsSequentialForIndependentBlocks() {
        // 20 independent blocks x 10ms scripted latency: sequential = ~200ms,
        // 4-way parallel = ~50ms. Mirrors the production per-block dispatch shape.
        val blocks = BenchmarkRunner.SAMPLE
        val latencyMs = 10L

        val sequentialEngine = ScriptedLatencyTranslator(latencyMs)
        val t0 = System.nanoTime()
        blocks.forEach { sequentialEngine.translatePage(listOf(it)) }
        val sequentialMs = (System.nanoTime() - t0) / 1_000_000
        println("ORCH-BENCH dispatch sequential(20x10ms): ${sequentialMs}ms")

        val pool = Executors.newFixedThreadPool(4)
        val parallelEngine = ScriptedLatencyTranslator(latencyMs)
        val t1 = System.nanoTime()
        val futures = blocks.map { b -> pool.submit<PageTranslation> { parallelEngine.translatePage(listOf(b)) } }
        futures.forEach { it.get(30, TimeUnit.SECONDS) }
        val parallelMs = (System.nanoTime() - t1) / 1_000_000
        pool.shutdownNow()
        println("ORCH-BENCH dispatch parallel x4(20x10ms): ${parallelMs}ms")

        assertTrue(
            "parallel (${parallelMs}ms) must beat sequential (${sequentialMs}ms)",
            parallelMs < sequentialMs
        )
    }
}
