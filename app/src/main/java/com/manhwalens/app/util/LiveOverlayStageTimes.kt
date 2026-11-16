package com.manhwalens.app.util

/** Monotonic first-overlay timing; values are durations only and contain no page data. */
internal data class LiveOverlayStageTimes(
    val mainThreadQueueMs: Long?,
    val callbackPreparationMs: Long?,
    val patchBuildMs: Long?,
    val overlayHandoffMs: Long?,
    val overlayApplyMs: Long?
) {
    fun toReportLine(): String {
        fun format(value: Long?): String = value?.let { "$it ms" } ?: "n/a"
        return "main queue=${format(mainThreadQueueMs)} · callback prep=${format(callbackPreparationMs)} " +
            "· patch build=${format(patchBuildMs)} · handoff=${format(overlayHandoffMs)} " +
            "· overlay apply=${format(overlayApplyMs)}"
    }

    companion object {
        fun measure(
            queuedAtElapsedMs: Long,
            callbackStartedAtElapsedMs: Long,
            patchBuildStartedAtElapsedMs: Long,
            patchBuildFinishedAtElapsedMs: Long,
            overlayApplyStartedAtElapsedMs: Long,
            outputAppliedAtElapsedMs: Long
        ) = LiveOverlayStageTimes(
            mainThreadQueueMs = duration(queuedAtElapsedMs, callbackStartedAtElapsedMs),
            callbackPreparationMs = duration(callbackStartedAtElapsedMs, patchBuildStartedAtElapsedMs),
            patchBuildMs = duration(patchBuildStartedAtElapsedMs, patchBuildFinishedAtElapsedMs),
            overlayHandoffMs = duration(patchBuildFinishedAtElapsedMs, overlayApplyStartedAtElapsedMs),
            overlayApplyMs = duration(overlayApplyStartedAtElapsedMs, outputAppliedAtElapsedMs)
        )

        private fun duration(start: Long, end: Long): Long? =
            if (start < 0L || end < start) null else end - start
    }
}
