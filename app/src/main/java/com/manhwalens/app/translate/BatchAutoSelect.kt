package com.manhwalens.app.translate

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Tiny on-device self-test (v0.9.9): 4 short strings per [SplitStrategy], each strategy run
 * [RUNS] times. A strategy qualifies only when EVERY run maps 100 % ([BatchSplitter.validate]).
 * The fastest qualifying strategy wins — unless plain parallel per-block calls are at least
 * as fast on the same strings, in which case parallel stays (joined would buy nothing).
 * Before a separator is adopted it must also survive ONE page-sized call ([CONFIRM], 10
 * bubbles): on the test device "@@@" lost its markers at exactly that size, and a 4-string
 * pass alone would have sent the first real page into a failed joined attempt.
 * No qualifying strategy ⇒ parallel.
 *
 * Pure logic: [translateOne] is the raw engine call (throws on failure).
 */
object BatchAutoSelect {

    val SELF_TEST: List<String> = listOf(
        "Wait, where are you going?",
        "Stay behind me and keep quiet.",
        "I will be back before sunrise.",
        "Thank you for everything."
    )

    /** Page-sized confirmation sample (the first 10 benchmark bubbles). */
    val CONFIRM: List<String> get() = BenchmarkRunner.SAMPLE.take(10).map { it.english }

    const val RUNS = 2
    const val PARALLEL_LIMIT = 2

    data class Trial(
        val strategy: SplitStrategy,
        val ok: Boolean,
        /** Fastest run, ms. */
        val elapsedMs: Long,
        val mismatched: Int,
        val reason: String
    )

    data class Choice(
        /** Null = parallel. */
        val strategy: SplitStrategy?,
        val trials: List<Trial>,
        val parallelMs: Long,
        val why: String
    )

    fun run(
        translateOne: (String) -> String,
        strategies: List<SplitStrategy> = SplitStrategy.values().toList(),
        sample: List<String> = SELF_TEST,
        confirmSample: List<String> = CONFIRM,
        clock: () -> Long = { System.nanoTime() / 1_000_000L }
    ): Choice {
        // Warm-up so the first strategy is not charged for a cold engine.
        runCatching { translateOne("hello") }

        val firstTrials = strategies.map { s ->
            var ok = true
            var best = Long.MAX_VALUE
            var mismatched = 0
            var reason = "ok"
            for (run in 0 until RUNS) {
                val t0 = clock()
                val v = try {
                    BatchSplitter.validate(s, sample, translateOne(BatchSplitter.join(s, sample)))
                } catch (e: Exception) {
                    BatchSplitter.Result(false, emptyList(), sample.size, "error: ${e.javaClass.simpleName}")
                }
                best = minOf(best, clock() - t0)
                if (!v.ok) {
                    ok = false
                    mismatched = maxOf(mismatched, v.mismatched)
                    reason = v.reason
                    break
                }
            }
            Trial(s, ok, best, mismatched, reason)
        }

        val parallelMs = timeParallel(translateOne, sample, clock)
        // Fastest first; a candidate is adopted only if it also maps one page-sized call.
        val rejected = ArrayList<String>()
        var confirmedTrials = firstTrials
        var winner: Trial? = null
        for (candidate in rankCandidates(firstTrials)) {
            val v = if (confirmSample.isEmpty()) null else try {
                BatchSplitter.validate(
                    candidate.strategy, confirmSample,
                    translateOne(BatchSplitter.join(candidate.strategy, confirmSample))
                )
            } catch (e: Exception) {
                BatchSplitter.Result(false, emptyList(), confirmSample.size, "error: ${e.javaClass.simpleName}")
            }
            if (v == null || v.ok) {
                winner = candidate
                break
            }
            rejected += candidate.strategy.label
            confirmedTrials = confirmedTrials.map {
                if (it.strategy == candidate.strategy) {
                    it.copy(ok = false, mismatched = v.mismatched, reason = "page-size check: ${v.reason}")
                } else it
            }
        }
        val trials = confirmedTrials
        return when {
            winner == null ->
                Choice(
                    null, trials, parallelMs,
                    "no separator mapped 100% (${trials.size} tried" +
                        (if (rejected.isEmpty()) "" else ", ${rejected.joinToString("/")} failed the page-size check") +
                        ") → parallel"
                )
            parallelMs in 0..winner.elapsedMs ->
                Choice(null, trials, parallelMs, "parallel ${parallelMs} ms ≤ best joined ${winner.strategy.label} ${winner.elapsedMs} ms")
            else ->
                Choice(
                    winner.strategy, trials, parallelMs,
                    "${winner.strategy.label} ${winner.elapsedMs} ms mapped ${sample.size}/${sample.size} ×$RUNS, " +
                        "fastest of ${trials.count { it.ok }} passing; parallel ${if (parallelMs < 0) "failed" else "$parallelMs ms"}"
                )
        }
    }

    /**
     * Passing candidates, fastest first — except that "•" goes first whenever it is within
     * [BULLET_SLACK] of the fastest. Field evidence (Redmi 13C): " | " won a 4-string timing
     * by noise and then lost a separator on a real page, while "•" mapped 20/20 in every
     * benchmark pass; on 4 short strings the timings differ by less than their jitter.
     */
    fun rankCandidates(trials: List<Trial>): List<Trial> {
        val byTime = trials.filter { it.ok }.sortedBy { it.elapsedMs }
        val fastest = byTime.firstOrNull() ?: return byTime
        val bullet = byTime.firstOrNull { it.strategy == SplitStrategy.BULLET } ?: return byTime
        return if (bullet.elapsedMs <= fastest.elapsedMs * BULLET_SLACK) {
            listOf(bullet) + byTime.filter { it !== bullet }
        } else byTime
    }

    const val BULLET_SLACK = 1.5

    /** Best of [RUNS] parallel per-block passes, or -1 when a call fails. */
    private fun timeParallel(translateOne: (String) -> String, sample: List<String>, clock: () -> Long): Long {
        val pool = Executors.newFixedThreadPool(PARALLEL_LIMIT)
        return try {
            var best = Long.MAX_VALUE
            for (run in 0 until RUNS) {
                val t0 = clock()
                val futures = sample.map { text -> pool.submit<String> { translateOne(text) } }
                if (futures.any { it.get(30, TimeUnit.SECONDS).isBlank() }) return -1L
                best = minOf(best, clock() - t0)
            }
            best
        } catch (e: Exception) {
            -1L
        } finally {
            pool.shutdownNow()
        }
    }
}
