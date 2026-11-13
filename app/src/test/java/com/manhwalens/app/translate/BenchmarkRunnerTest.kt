package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class BenchmarkRunnerTest {

    /** Fake engine that translates words to Arabic and respects every separator. */
    private val goodEngine = FakeArabicEngine.translate

    private val methodCount = 1 + BenchmarkRunner.PARALLEL_LEVELS.size + SplitStrategy.values().size

    @Test
    fun sampleHas20Bubbles() {
        assertEquals(20, BenchmarkRunner.SAMPLE.size)
        assertEquals((1..20).toList(), BenchmarkRunner.SAMPLE.map { it.id })
    }

    @Test
    fun allMethodsMap_whenEngineRespectsSeparators() {
        val report = BenchmarkRunner.run(goodEngine) { 0L }
        assertEquals(methodCount, report.strategies.size)
        for (r in report.strategies) assertTrue("${r.name}: ${r.detail}", r.mappedOk)
        assertTrue(report.strategies.all { it.elapsedMs >= 0 && it.mismatched == 0 })
        // every separator and every parallel level is listed
        for (s in SplitStrategy.values()) assertTrue(report.strategies.any { it.strategy == s })
        assertEquals(listOf(1, 2, 3, 4, 6), report.strategies.filter { it.parallelLevel > 0 }.map { it.parallelLevel })
    }

    @Test
    fun joinedStrategyReportsMismatch_whenModelEatsSeparator() {
        // Model that eats "@@@" (the Redmi 13C result) but keeps other separators.
        val badEngine: (String) -> String = { goodEngine(it).replace("\n@@@\n", " ") }
        val report = BenchmarkRunner.run(badEngine) { 0L }
        val joined = report.strategies.first { it.strategy == SplitStrategy.AT }
        assertTrue(joined.name.contains("Joined"))
        assertFalse(joined.mappedOk)
        assertTrue(joined.detail.contains("split mismatch"))
        assertEquals(19, joined.mismatched) // 1 segment came back instead of 20
        // per-block methods and the other separators are unaffected
        assertTrue(report.strategies.first { it.name.contains("Sequential") }.mappedOk)
        assertTrue(report.strategies.filter { it.name.contains("Parallel") }.all { it.mappedOk })
        assertTrue(report.strategies.first { it.strategy == SplitStrategy.HASH }.mappedOk)
    }

    @Test
    fun engineExceptions_areCapturedNotThrown() {
        val boom: (String) -> String = { throw IllegalStateException("engine down") }
        val report = BenchmarkRunner.run(boom) { 0L }
        assertEquals(methodCount, report.strategies.size)
        assertTrue(report.strategies.all { !it.mappedOk })
        assertTrue(report.strategies.all { it.detail.contains("error") })
        assertNull(report.recommend().strategy)
    }

    @Test
    fun fivePasses_firstDiscarded_medianAndWorstReported() {
        // Joined "###" call costs 900 (warm-up), then 10, 30, 20, 40 → median 25, worst 40.
        val costs = listOf(900L, 10L, 30L, 20L, 40L)
        val calls = AtomicInteger(0)
        var now = 0L
        val engine: (String) -> String = { input ->
            if (input.contains("###")) now += costs[calls.getAndIncrement()]
            goodEngine(input)
        }
        val report = BenchmarkRunner.run(engine) { now }
        val hash = report.strategies.first { it.strategy == SplitStrategy.HASH }
        assertEquals(5, calls.get())
        assertEquals(25L, hash.elapsedMs)
        assertEquals(40L, hash.worstMs)
        assertEquals(5, report.passes)
    }

    @Test
    fun methodFailingInOnlyOnePass_isMapFail() {
        val calls = AtomicInteger(0)
        val flaky: (String) -> String = { input ->
            val out = goodEngine(input)
            if (input.contains(" | ") && calls.incrementAndGet() == 4) out.replace(" | ", " ") else out
        }
        val pipe = BenchmarkRunner.run(flaky) { 0L }.strategies.first { it.strategy == SplitStrategy.PIPE }
        assertFalse(pipe.mappedOk)
        assertEquals(19, pipe.mismatched)
    }

    @Test
    fun recommend_joinedOnlyWhenItMapsAndBeatsParallel() {
        fun row(name: String, ms: Long, ok: Boolean, s: SplitStrategy? = null, level: Int = 0) =
            BenchmarkRunner.StrategyResult(name, ms, ms, ok, if (ok) 0 else 3, "", s, level)

        // fast joined that FAILS is ignored; slower passing joined still beats parallel
        val a = BenchmarkRunner.Report(
            listOf(
                row("Parallel x3", 480, true, level = 3),
                row("Joined @@@", 320, false, SplitStrategy.AT),
                row("Joined \\n", 350, true, SplitStrategy.NEWLINE)
            ), 20, 5
        ).recommend()
        assertEquals(SplitStrategy.NEWLINE, a.strategy)

        // no passing joined → best parallel level
        val b = BenchmarkRunner.Report(
            listOf(
                row("Parallel x3", 480, true, level = 3),
                row("Parallel x4", 450, true, level = 4),
                row("Joined @@@", 320, false, SplitStrategy.AT)
            ), 20, 5
        ).recommend()
        assertNull(b.strategy)
        assertEquals(4, b.parallelLevel)

        // Redmi 13C numbers: x2 is the best per-block level, sequential beats x3
        val device = BenchmarkRunner.Report(
            listOf(
                row("Sequential", 1022, true, level = 1), row("Parallel x2", 950, true, level = 2),
                row("Parallel x3", 1254, true, level = 3), row("Parallel x4", 1624, true, level = 4),
                row("Joined •", 714, true, SplitStrategy.BULLET)
            ), 20, 5
        )
        assertEquals(2, device.bestPerBlockLevel())
        // a noisy run where x6 edges out x2 by a few ms must not move the level…
        val noisy = BenchmarkRunner.Report(
            listOf(row("Parallel x2", 950, true, level = 2), row("Parallel x6", 900, true, level = 6)), 20, 5
        )
        assertEquals(2, noisy.bestPerBlockLevel())
        // …a clear ≥15 % win does.
        val clear = BenchmarkRunner.Report(
            listOf(row("Parallel x2", 950, true, level = 2), row("Parallel x4", 700, true, level = 4)), 20, 5
        )
        assertEquals(4, clear.bestPerBlockLevel())
        assertEquals(SplitStrategy.BULLET, device.recommend().strategy)

        // passing joined that is slower than parallel → parallel
        val c = BenchmarkRunner.Report(
            listOf(row("Parallel x3", 300, true, level = 3), row("Joined ###", 400, true, SplitStrategy.HASH)), 20, 5
        ).recommend()
        assertNull(c.strategy)
    }

    @Test
    fun reportText_isATableWithEveryMethod_mapColumn_andExtras() {
        val badEngine: (String) -> String = { goodEngine(it).replace("\n@@@\n", " ") }
        val report = BenchmarkRunner.run(badEngine) { 0L }
        val text = report.toText(listOf("Runtime now: parallel"))
        for (s in report.strategies) assertTrue(s.name, text.contains(s.name))
        assertTrue(text.contains("median"))
        assertTrue(text.contains("worst"))
        assertTrue(text.contains("map OK"))
        assertTrue(text.contains("map FAIL"))
        assertTrue(text.contains("20 bubbles"))
        assertTrue(text.contains("Benchmark verdict:"))
        assertTrue(text.contains("Runtime now: parallel"))
    }

    @Test
    fun parallelStrategy_actuallyOverlapsCalls() {
        val current = AtomicInteger(0)
        val maxConcurrent = AtomicInteger(0)
        val slowEngine: (String) -> String = { input ->
            val now = current.incrementAndGet()
            maxConcurrent.updateAndGet { maxOf(it, now) }
            Thread.sleep(2)
            current.decrementAndGet()
            goodEngine(input)
        }
        BenchmarkRunner.run(slowEngine, passes = 2)
        assertTrue("parallel should overlap (max=${maxConcurrent.get()})", maxConcurrent.get() >= 2)
        assertTrue("never more than 6 in flight (max=${maxConcurrent.get()})", maxConcurrent.get() <= 6)
    }
}
