package com.manhwalens.app.translate

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * On-device self-test: short strings pre-screen each [SplitStrategy] with median timings.
 * Every run must map 100 %. Before adoption, a candidate must also pass two full 20-bubble
 * confirmations. The fastest qualifying strategy wins unless parallel is at least as fast.
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

    /** Full-page confirmation sample (all 20 benchmark bubbles used by the device benchmark). */
    val CONFIRM: List<String> get() = BenchmarkRunner.SAMPLE.map { it.english }

    const val RUNS = 3
    const val CONFIRM_RUNS = 2
    const val PARALLEL_LIMIT = 2
    const val MIN_JOINED_WIN_PERCENT = 10

    data class Trial(
        val strategy: SplitStrategy,
        val ok: Boolean,
        /** Median run time, ms. */
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
        parallelLimit: Int = PARALLEL_LIMIT,
        clock: () -> Long = { System.nanoTime() / 1_000_000L }
    ): Choice {
        // Warm-up so the first strategy is not charged for a cold engine.
        runCatching { translateOne("hello") }

        val firstTrials = strategies.map { s ->
            var ok = true
            val durations = ArrayList<Long>(RUNS)
            var mismatched = 0
            var reason = "ok"
            for (run in 0 until RUNS) {
                val t0 = clock()
                val v = try {
                    BatchSplitter.validate(s, sample, translateOne(BatchSplitter.join(s, sample)))
                } catch (e: Exception) {
                    BatchSplitter.Result(false, emptyList(), sample.size, "error: ${e.javaClass.simpleName}")
                }
                durations += (clock() - t0).coerceAtLeast(0L)
                if (!v.ok) {
                    ok = false
                    mismatched = maxOf(mismatched, v.mismatched)
                    reason = v.reason
                    break
                }
            }
            Trial(s, ok, median(durations), mismatched, reason)
        }

        val parallelMs = timeParallel(translateOne, sample, parallelLimit, clock)
        // Fastest first; a candidate is adopted only if it also maps one page-sized call.
        val rejected = ArrayList<String>()
        var confirmedTrials = firstTrials
        var winner: Trial? = null
        for (candidate in rankCandidates(firstTrials)) {
            var pageFailure: BatchSplitter.Result? = null
            repeat(if (confirmSample.isEmpty()) 0 else CONFIRM_RUNS) { pageRun ->
                if (pageFailure == null) {
                    val v = try {
                        BatchSplitter.validate(
                            candidate.strategy, confirmSample,
                            translateOne(BatchSplitter.join(candidate.strategy, confirmSample))
                        )
                    } catch (e: Exception) {
                        BatchSplitter.Result(false, emptyList(), confirmSample.size, "error: ${e.javaClass.simpleName}")
                    }
                    if (!v.ok) pageFailure = v.copy(reason = "confirmation ${pageRun + 1}/$CONFIRM_RUNS: ${v.reason}")
                }
            }
            if (pageFailure == null) {
                winner = candidate
                break
            }
            val failure = pageFailure!!
            rejected += candidate.strategy.label
            confirmedTrials = confirmedTrials.map {
                if (it.strategy == candidate.strategy) {
                    it.copy(ok = false, mismatched = failure.mismatched, reason = "page-size check: ${failure.reason}")
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
            parallelMs >= 0 && winner.elapsedMs * 100 > parallelMs * (100 - MIN_JOINED_WIN_PERCENT) ->
                Choice(null, trials, parallelMs, "parallel ${parallelMs} ms retained; joined ${winner.strategy.label} ${winner.elapsedMs} ms was less than ${MIN_JOINED_WIN_PERCENT}% faster")
            else ->
                Choice(
                    winner.strategy, trials, parallelMs,
                    "${winner.strategy.label} ${winner.elapsedMs} ms mapped ${sample.size}/${sample.size} ×$RUNS, " +
                        "fastest of ${trials.count { it.ok }} passing; parallel ${if (parallelMs < 0) "failed" else "$parallelMs ms"}"
                )
        }
    }

    /** Passing candidates, fastest first; no separator receives a hard-coded preference. */
    fun rankCandidates(trials: List<Trial>): List<Trial> =
        trials.filter { it.ok }.sortedBy { it.elapsedMs }

    private fun median(values: List<Long>): Long {
        if (values.isEmpty()) return -1L
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    /** Median of [RUNS] parallel per-block passes, or -1 when a call fails. */
    private fun timeParallel(translateOne: (String) -> String, sample: List<String>, limit: Int, clock: () -> Long): Long {
        val pool = Executors.newFixedThreadPool(limit.coerceIn(1, 6))
        return try {
            val durations = ArrayList<Long>(RUNS)
            for (run in 0 until RUNS) {
                val t0 = clock()
                val futures = sample.map { text -> pool.submit<String> { translateOne(text) } }
                if (futures.any { it.get(30, TimeUnit.SECONDS).isBlank() }) return -1L
                durations += (clock() - t0).coerceAtLeast(0L)
            }
            median(durations)
        } catch (e: Exception) {
            -1L
        } finally {
            pool.shutdownNow()
        }
    }
}
