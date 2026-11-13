package com.manhwalens.app.translate

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Settings → Diagnostics benchmark (v0.9.9). Translates a fixed 20-bubble sample page with
 * every method, [passes] times each; the first pass is a warm-up and is discarded, the rest
 * give the median and the worst time.
 *
 * Methods:
 *  - sequential (one call per bubble)
 *  - parallel per-bubble calls, 2 / 3 / 4 / 6 in flight
 *  - one joined call per [SplitStrategy], validated by [BatchSplitter.validate]
 *
 * A joined method is "map OK" only when EVERY measured pass maps 100 %; the table also shows
 * the worst number of mismatched segments. [translateOne] is the raw engine call (throws on
 * failure) — the ML Kit binding lives in the caller, so this is unit-testable with a fake.
 */
object BenchmarkRunner {

    data class StrategyResult(
        val name: String,
        /** Median of the measured passes, ms. */
        val elapsedMs: Long,
        val worstMs: Long,
        val mappedOk: Boolean,
        /** Worst number of segments/bubbles that did not map in any pass. */
        val mismatched: Int,
        val detail: String,
        /** Set for joined methods. */
        val strategy: SplitStrategy? = null,
        /** Set for per-bubble methods (1 = sequential). */
        val parallelLevel: Int = 0
    )

    data class Recommendation(val strategy: SplitStrategy?, val parallelLevel: Int, val why: String)

    data class Report(
        val strategies: List<StrategyResult>,
        val bubbles: Int,
        val passes: Int
    ) {
        /** Fastest method that mapped every pass; joined only when it beats the best parallel. */
        fun recommend(): Recommendation {
            val bestParallel = strategies.filter { it.parallelLevel >= 2 && it.mappedOk }.minByOrNull { it.elapsedMs }
            val bestJoined = strategies.filter { it.strategy != null && it.mappedOk }.minByOrNull { it.elapsedMs }
            return when {
                bestJoined != null && (bestParallel == null || bestJoined.elapsedMs < bestParallel.elapsedMs) ->
                    Recommendation(
                        bestJoined.strategy, 0,
                        "joined ${bestJoined.strategy?.label} median ${bestJoined.elapsedMs} ms mapped every pass" +
                            (bestParallel?.let { " vs parallel x${it.parallelLevel} ${it.elapsedMs} ms" } ?: "")
                    )
                bestParallel != null ->
                    Recommendation(
                        null, bestParallel.parallelLevel,
                        "parallel x${bestParallel.parallelLevel} median ${bestParallel.elapsedMs} ms" +
                            (bestJoined?.let { " ≤ best joined ${it.strategy?.label} ${it.elapsedMs} ms" }
                                ?: " — no separator mapped every pass")
                    )
                else -> Recommendation(null, 3, "nothing mapped — keeping parallel x3")
            }
        }

        /** Fastest per-block level that mapped (1 = sequential); 2 when nothing was measured. */
        fun bestPerBlockLevel(): Int {
            val ok = strategies.filter { it.parallelLevel >= 1 && it.mappedOk }
            val best = ok.minByOrNull { it.elapsedMs } ?: return BatchPolicy.DEFAULT_PARALLEL
            // v0.9.13: one run is noisy (x6 "won" a run on the phone that had measured it 50 %
            // slower than x2 minutes earlier). Leave the default only for a clear ≥15 % win.
            val base = ok.firstOrNull { it.parallelLevel == BatchPolicy.DEFAULT_PARALLEL } ?: return best.parallelLevel
            return if (best.elapsedMs * 100 <= base.elapsedMs * 85) best.parallelLevel else BatchPolicy.DEFAULT_PARALLEL
        }

        fun toText(extraLines: List<String> = emptyList()): String = buildString {
            appendLine("ManhwaLens translation benchmark (ML Kit, EN→AR)")
            appendLine("Sample: $bubbles bubbles · $passes passes per method, first discarded (warm-up) · median / worst of ${passes - 1}")
            appendLine()
            appendLine("${"method".padEnd(22)} ${"median".padStart(7)} ${"worst".padStart(7)}  map       bad  detail")
            for (r in strategies) {
                val mark = if (r.mappedOk) "map OK  " else "map FAIL"
                appendLine(
                    "${r.name.padEnd(22)} ${(r.elapsedMs.toString() + "ms").padStart(7)} ${(r.worstMs.toString() + "ms").padStart(7)}  " +
                        "$mark  ${r.mismatched.toString().padStart(3)}  ${r.detail}"
                )
            }
            appendLine()
            appendLine("Benchmark verdict: ${recommend().why}")
            for (l in extraLines) appendLine(l)
        }.trim()
    }

    /** Fixed sample page: 20 bubbles, mixed lengths incl. SFX, questions and numbers. */
    val SAMPLE: List<TranslationBlock> = listOf(
        "ALRIGHT, I'LL HAVE A GLASS.",
        "Wait… you made it! I wasn't sure you'd come.",
        "KRAK",
        "Where are you going? Come back here.",
        "The gate opens at dawn. We leave before sunrise.",
        "Stay behind me and don't say a word.",
        "Don't be stupid. This is suicide.",
        "I've been waiting for three years for this meeting.",
        "Again? You always do this to me.",
        "Wei Wuxian, hand over the Stygian Tiger Seal or Lan Wangji dies here.",
        "Elder Wei, the Jiang clan will not forgive this insult!",
        "No.",
        "What did you just say?",
        "My lord, the northern army has crossed the river.",
        "I don't believe you.",
        "Then prove it. Show me your sword.",
        "It hurts… but I can still stand.",
        "Run!",
        "Who gave you permission to enter this room?",
        "From now on, you are my student."
    ).mapIndexed { i, s -> TranslationBlock(i + 1, s) }

    val PARALLEL_LEVELS = listOf(2, 3, 4, 6)
    const val DEFAULT_PASSES = 5

    /**
     * @param translateOne raw engine call; must throw on failure
     * @param blocks sample to benchmark (defaults to [SAMPLE])
     * @param passes passes per method incl. the discarded warm-up (min 2)
     * @param clock injectable for unit tests (ms)
     */
    fun run(
        translateOne: (String) -> String,
        blocks: List<TranslationBlock> = SAMPLE,
        passes: Int = DEFAULT_PASSES,
        clock: () -> Long = { System.nanoTime() / 1_000_000L }
    ): Report {
        val n = passes.coerceAtLeast(2)
        // v0.9.14: time exactly what a real page sends (names resolved, lines joined).
        val sources = blocks.map { Glossary.prepare(it.english) }

        /** Runs [pass] n times; pass returns the number of mismatched items, or throws. */
        fun measure(name: String, okDetail: String, strategy: SplitStrategy?, level: Int, pass: () -> Pair<Int, String>): StrategyResult {
            val times = ArrayList<Long>(n)
            var worstBad = 0
            var badReason = ""
            for (i in 0 until n) {
                val t0 = clock()
                val (bad, reason) = try {
                    pass()
                } catch (e: Exception) {
                    return StrategyResult(name, clock() - t0, clock() - t0, false, blocks.size, "error: ${brief(e)}", strategy, level)
                }
                val dt = clock() - t0
                if (i > 0) times += dt // pass 0 = warm-up, discarded
                if (bad > worstBad) {
                    worstBad = bad
                    badReason = reason
                }
            }
            val sorted = times.sorted()
            val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
            else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
            return StrategyResult(
                name, median, sorted.last(), worstBad == 0, worstBad,
                if (worstBad == 0) okDetail else "split mismatch — $badReason",
                strategy, level
            )
        }

        fun perBlock(level: Int): StrategyResult {
            val name = if (level <= 1) "Sequential" else "Parallel x$level"
            if (level <= 1) {
                return measure(name, "${blocks.size} calls", null, 1) {
                    val bad = sources.count { translateOne(it).isBlank() }
                    bad to "blank result"
                }
            }
            val pool = Executors.newFixedThreadPool(level)
            return try {
                measure(name, "${blocks.size} calls, $level in flight", null, level) {
                    val futures = sources.map { s -> pool.submit<String> { translateOne(s) } }
                    val bad = futures.count { it.get(30, TimeUnit.SECONDS).isBlank() }
                    bad to "blank result"
                }
            } finally {
                pool.shutdownNow()
            }
        }

        fun joined(s: SplitStrategy): StrategyResult =
            measure("Joined ${s.label}", "1 call, ${blocks.size}/${blocks.size} mapped", s, 0) {
                val v = BatchSplitter.validate(s, sources, translateOne(BatchSplitter.join(s, sources)))
                v.mismatched to v.reason
            }

        val out = ArrayList<StrategyResult>()
        out += perBlock(1)
        for (level in PARALLEL_LEVELS) out += perBlock(level)
        for (s in SplitStrategy.values()) out += joined(s)
        return Report(out, blocks.size, n)
    }

    private fun brief(e: Throwable): String {
        val root = e.cause ?: e
        return (root.message ?: root.javaClass.simpleName).take(120).replace('\n', ' ')
    }
}
