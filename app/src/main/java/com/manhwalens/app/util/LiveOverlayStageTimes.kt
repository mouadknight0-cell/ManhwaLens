package com.manhwalens.app.util

/** Monotonic first-overlay timing; durations only, no page data. */
internal data class LiveOverlayStageTimes(
    val patchBuildMs: Long?,
    val workerHandoffMs: Long?,
    val mainThreadQueueMs: Long?,
    val callbackPreparationMs: Long?,
    val overlayApplyMs: Long?
) {
    fun toReportLine(): String {
        fun f(v: Long?) = v?.let { "$it ms" } ?: "n/a"
        return "patch build=${f(patchBuildMs)} · worker handoff=${f(workerHandoffMs)} " +
            "· main queue=${f(mainThreadQueueMs)} · callback prep=${f(callbackPreparationMs)} " +
            "· overlay apply=${f(overlayApplyMs)}"
    }

    companion object {
        fun measure(
            patchBuildStartedAtElapsedMs: Long,
            patchBuildFinishedAtElapsedMs: Long,
            queuedAtElapsedMs: Long,
            callbackStartedAtElapsedMs: Long,
            overlayApplyStartedAtElapsedMs: Long,
            outputAppliedAtElapsedMs: Long
        ) = LiveOverlayStageTimes(
            patchBuildMs = duration(patchBuildStartedAtElapsedMs, patchBuildFinishedAtElapsedMs),
            workerHandoffMs = duration(patchBuildFinishedAtElapsedMs, queuedAtElapsedMs),
            mainThreadQueueMs = duration(queuedAtElapsedMs, callbackStartedAtElapsedMs),
            callbackPreparationMs = duration(callbackStartedAtElapsedMs, overlayApplyStartedAtElapsedMs),
            overlayApplyMs = duration(overlayApplyStartedAtElapsedMs, outputAppliedAtElapsedMs)
        )

        private fun duration(start: Long, end: Long): Long? =
            if (start < 0L || end < start) null else end - start
    }
}
