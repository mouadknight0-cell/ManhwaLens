package com.manhwalens.app.translate

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Executes the ML Kit side of the engine comparison using this device's saved page strategy.
 *
 * The caller owns the exclusive diagnostic gate and supplies a raw translator callback that
 * is safe to call from worker threads. This helper reads a snapshot of the strategy only; it
 * never changes the saved BatchPolicy or the selected translation engine.
 */
object MlKitDiagnosticBatch {
    data class Result(val outputs: List<String>, val dispatch: String, val retriedCount: Int = 0)

    private const val JOINED_MAX_CHARS = 3000
    private const val REQUEST_TIMEOUT_SECONDS = 20L

    fun translate(
        inputs: List<String>,
        parallelLevel: Int,
        joinedStrategy: SplitStrategy?,
        translateOne: (String) -> String
    ): Result {
        if (inputs.isEmpty()) return Result(emptyList(), "no inputs")
        val level = parallelLevel.coerceIn(1, 6)

        if (joinedStrategy != null && inputs.size >= 2) {
            val joinedEligible = inputs.sumOf { it.length } <= JOINED_MAX_CHARS &&
                BatchSplitter.sourcesSafe(joinedStrategy, inputs)
            if (joinedEligible) {
                val joinedOutput = runCatching {
                    translateOne(BatchSplitter.join(joinedStrategy, inputs))
                }.getOrNull()
                if (joinedOutput != null) {
                    val mapped = BatchSplitter.validate(joinedStrategy, inputs, joinedOutput)
                    if (mapped.ok) {
                        return Result(mapped.segments, "joined ${joinedStrategy.label}")
                    }
                }
            }
        }

        val pool = Executors.newFixedThreadPool(level)
        return try {
            val futures = inputs.map { input ->
                pool.submit(Callable { translateOne(input).trim() })
            }
            val outputs = futures.map { future ->
                try {
                    future.get(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                } catch (e: TimeoutException) {
                    future.cancel(true)
                    ""
                } catch (_: Exception) {
                    ""
                }
            }.toMutableList()
            // Match the production ML Kit page path: keep successful parallel replies,
            // then retry only blank/missing bubbles once, sequentially.
            var retried = 0
            for (index in outputs.indices) {
                if (outputs[index].isBlank()) {
                    retried++
                    outputs[index] = runCatching { translateOne(inputs[index]).trim() }.getOrDefault("")
                }
            }
            val reason = when {
                joinedStrategy == null -> ""
                inputs.sumOf { it.length } > JOINED_MAX_CHARS -> " (joined skipped: input too long)"
                !BatchSplitter.sourcesSafe(joinedStrategy, inputs) -> " (joined skipped: marker in source)"
                else -> " (joined ${joinedStrategy.label} did not map; parallel fallback)"
            }
            val retryNote = if (retried == 0) "" else "; sequential retry x$retried"
            Result(outputs, "parallel x$level$reason$retryNote", retried)
        } finally {
            pool.shutdownNow()
        }
    }
}
