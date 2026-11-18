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
        val parallelLevel: Int = 0,
        /** Individual measured samples, excluding the warm-up pass. */
        val samplesMs: List<Long> = emptyList(),
        /** max(sample) - min(sample), a simple noise indicator. */
        val spreadMs: Long = 0
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
            appendLine("ML Kit batching benchmark (EN→AR)")
            appendLine("Engine: ML Kit only. The selected offline engine setting does not affect this diagnostic.")
            appendLine("Sample: $bubbles bubbles · $passes passes per method, first discarded (warm-up) · interleaved order · median / worst / range of ${passes - 1}")
            appendLine()
            appendLine("${"method".padEnd(22)} ${"median".padStart(7)} ${"worst".padStart(7)} ${"range".padStart(7)}  map       bad  detail")
            for (r in strategies) {
                val mark = if (r.mappedOk) "map OK  " else "map FAIL"
                appendLine(
                    "${r.name.padEnd(22)} ${(r.elapsedMs.toString() + "ms").padStart(7)} ${(r.worstMs.toString() + "ms").padStart(7)}  " +
                        "${r.spreadMs.toString().padStart(5)}  $mark  ${r.mismatched.toString().padStart(3)}  ${r.detail}" +
                        (r.samplesMs.takeIf { it.isNotEmpty() }?.let { " · runs=${it.joinToString("/")}" } ?: "")
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
        val sources = blocks.map { Glossary.prepare(it.english) }

        data class Method(
            val name: String,
            val okDetail: String,
            val strategy: SplitStrategy?,
            val level: Int,
            val pass: () -> Pair<Int, String>
        )
        data class State(
            val method: Method,
            val samples: MutableList<Long> = ArrayList(),
            var worstBad: Int = 0,
            var badReason: String = "",
            var error: String? = null
        )

        val methods = ArrayList<Method>()
        val pools = ArrayList<java.util.concurrent.ExecutorService>()
        methods += Method("Sequential", "${blocks.size} calls", null, 1) {
            sources.count { translateOne(it).isBlank() } to "blank result"
        }
        for (level in PARALLEL_LEVELS) {
            val pool = Executors.newFixedThreadPool(level)
            pools += pool
            methods += Method("Parallel x$level", "${blocks.size} calls, $level in flight", null, level) {
                val futures = sources.map { source -> pool.submit<String> { translateOne(source) } }
                val bad = futures.count { it.get(30, TimeUnit.SECONDS).isBlank() }
                bad to "blank result"
            }
        }
        for (strategy in SplitStrategy.values()) {
            methods += Method("Joined ${strategy.label}", "1 call, ${blocks.size}/${blocks.size} mapped", strategy, 0) {
                val result = BatchSplitter.validate(
                    strategy, sources, translateOne(BatchSplitter.join(strategy, sources))
                )
                result.mismatched to result.reason
            }
        }

        val states = methods.map { State(it) }
        fun measureOnce(state: State, keepSample: Boolean) {
            val t0 = clock()
            var bad = 0
            var reason = ""
            try {
                val result = state.method.pass()
                bad = result.first
                reason = result.second
            } catch (e: Exception) {
                bad = blocks.size
                reason = "error: ${brief(e)}"
                state.error = reason
            }
            val elapsed = (clock() - t0).coerceAtLeast(0L)
            if (keepSample) state.samples += elapsed
            if (bad > state.worstBad) {
                state.worstBad = bad
                state.badReason = reason
            }
        }

        try {
            // Give every strategy one discarded warm-up before collecting any result.
            states.forEach { measureOnce(it, keepSample = false) }

            // Interleave measured rounds to reduce order, heat and background-load bias.
            // Rotating the start point and alternating direction adds no engine calls.
            for (round in 0 until (n - 1)) {
                val offset = (round * 5) % states.size.coerceAtLeast(1)
                val order = states.indices.map { (offset + it) % states.size }
                val arranged = if (round % 2 == 0) order else order.asReversed()
                arranged.forEach { index -> measureOnce(states[index], keepSample = true) }
            }
        } finally {
            pools.forEach { it.shutdownNow() }
        }

        val results = states.map { state ->
            val sorted = state.samples.sorted()
            val median = if (sorted.isEmpty()) 0L else if (sorted.size % 2 == 1) {
                sorted[sorted.size / 2]
            } else {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
            }
            val worst = sorted.maxOrNull() ?: 0L
            val spread = if (sorted.size < 2) 0L else (sorted.last() - sorted.first()).coerceAtLeast(0L)
            val detail = state.error ?: if (state.worstBad == 0) state.method.okDetail
                else "split mismatch — ${state.badReason}"
            StrategyResult(
                state.method.name, median, worst, state.worstBad == 0, state.worstBad,
                detail, state.method.strategy, state.method.level, state.samples.toList(), spread
            )
        }
        return Report(results, blocks.size, n)
    }

    private fun brief(e: Throwable): String {
        val root = e.cause ?: e
        return (root.message ?: root.javaClass.simpleName).take(120).replace('\n', ' ')
    }
}
