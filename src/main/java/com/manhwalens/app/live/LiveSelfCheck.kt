package com.manhwalens.app.live

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pure-JVM builder for the "Live self-check" screen (v0.9.5 · Part 2).
 *
 * Twelve stages, each PASS / FAIL / WAIT, in pipeline order — permission → session → first
 * frame → frames/s → loop → last gate → tracker verdict → stash → OCR → translation →
 * patches painted → last removal reason. If Live does not work on the device, exactly one
 * line says where it stopped.
 *
 * The service fills [Input] from real state; the Compose screen renders [stages]; [report]
 * produces the plain text for "Copy report". No Android types here.
 */
object LiveSelfCheck {

    enum class Result(val wire: String) { PASS("PASS"), FAIL("FAIL"), WAIT("WAIT") }

    data class Stage(val id: Int, val name: String, val result: Result, val detail: String) {
        fun line(): String = "${result.wire.padEnd(4)} ${id.toString().padStart(2)}. $name — $detail"
    }

    /**
     * @param firstFrameMs ms between session start and the first frame (−1 = never)
     * @param sessionAgeMs ms since the capture session was created (−1 = no session)
     * @param lastGate wire form of the gate that stopped the last tick ("none" = it ran)
     */
    data class Input(
        val overlayPermission: Boolean,
        val liveMode: Boolean,
        val projectionActive: Boolean,
        val sessionExists: Boolean,
        val sessionAgeMs: Long,
        val firstFrameMs: Long,
        val framesPerSecond: Float,
        val framesTotal: Long,
        val idleTicks: Long,
        val frameOrigin: String,
        val loopActive: Boolean,
        val lastGate: String,
        val lastGateAction: String,
        val gateHits: String,
        val verdict: String,
        val msSinceChange: Long,
        val diff: Float,
        val stash: String,
        val ocrLines: Int,
        val ocrMs: Long,
        val ocrAgeMs: Long,
        val translateEngine: String,
        val translateMs: Long,
        val translateError: String?,
        val translateAgeMs: Long,
        val patchesPainted: Int,
        val patchesExpected: Int,
        val lastRemoval: String,
        val liveStatus: String,
        val heals: String
    )

    /** No frame within this long after the session started → the mirror is dead. */
    const val NO_FRAME_FAIL_AFTER_MS = 3_000L

    /** OCR/translation results older than this are shown as stale (still PASS, but noted). */
    const val STALE_RESULT_MS = 60_000L

    fun stages(input: Input): List<Stage> = listOf(
        // 1 — overlay permission
        Stage(
            1, "overlay permission",
            if (input.overlayPermission) Result.PASS else Result.FAIL,
            if (input.overlayPermission) "SYSTEM_ALERT_WINDOW granted"
            else "SYSTEM_ALERT_WINDOW missing — nothing can be drawn"
        ),

        // 2 — capture session exists
        Stage(
            2, "capture session",
            when {
                input.sessionExists && input.projectionActive -> Result.PASS
                input.liveMode -> Result.FAIL
                else -> Result.WAIT
            },
            when {
                input.sessionExists && input.projectionActive ->
                    "projection active · virtual display alive · session ${input.sessionAgeMs}ms old"
                input.projectionActive -> "projection active but NO virtual display/ImageReader"
                input.liveMode -> "no MediaProjection — tap the lens and grant capture consent"
                else -> "no MediaProjection (Live is OFF)"
            }
        ),

        // 3 — first frame received
        Stage(
            3, "first frame",
            when {
                input.firstFrameMs >= 0 -> Result.PASS
                !input.sessionExists -> Result.WAIT
                input.sessionAgeMs > NO_FRAME_FAIL_AFTER_MS -> Result.FAIL
                else -> Result.WAIT
            },
            when {
                input.firstFrameMs >= 0 -> "received ${input.firstFrameMs}ms after session start"
                !input.sessionExists -> "no session yet"
                input.sessionAgeMs > NO_FRAME_FAIL_AFTER_MS ->
                    "NONE in ${input.sessionAgeMs}ms — mirror is not delivering (FLAG_SECURE app? display off?)"
                else -> "waiting (${input.sessionAgeMs}ms so far)"
            }
        ),

        // 4 — frames per second now
        Stage(
            4, "frames/s",
            when {
                input.framesTotal > 0 -> Result.PASS
                !input.sessionExists -> Result.WAIT
                input.sessionAgeMs > NO_FRAME_FAIL_AFTER_MS -> Result.FAIL
                else -> Result.WAIT
            },
            "now=${fmt(input.framesPerSecond)} total=${input.framesTotal} idleTicks=${input.idleTicks}" +
                " source=${input.frameOrigin}" +
                if (input.framesTotal > 0 && input.framesPerSecond < 0.1f)
                    " (screen still — the loop reuses the stash, this is normal)"
                else ""
        ),

        // 5 — loop active
        Stage(
            5, "loop active",
            when {
                input.loopActive && input.liveMode -> Result.PASS
                input.liveMode -> Result.FAIL
                else -> Result.WAIT
            },
            "loopActive=${input.loopActive} liveMode=${input.liveMode} status=${input.liveStatus}"
        ),

        // 6 — last gate that returned early and why
        Stage(
            6, "last gate",
            when {
                // v0.9.12: Live switched OFF by the user is not a failure of the pipeline.
                gateNameOf(input.lastGate) == LiveGates.GATE_LIVE_MODE -> Result.WAIT
                else -> when (LiveGates.severity(gateNameOf(input.lastGate))) {
                LiveGates.Severity.OK -> Result.PASS
                LiveGates.Severity.WAITING -> Result.WAIT
                LiveGates.Severity.BLOCKED -> Result.FAIL
                }
            },
            if (gateNameOf(input.lastGate) == LiveGates.GATE_NONE)
                "none — the tick reached the driver (hits: ${input.gateHits})"
            else
                "${input.lastGate} · ${input.lastGateAction} (hits: ${input.gateHits})" +
                    if (input.heals.isNotBlank()) " · heals: ${input.heals}" else ""
        ),

        // 7 — tracker verdict + ms since last change
        Stage(
            7, "tracker verdict",
            when {
                input.verdict == "stable" -> Result.PASS
                input.verdict == "none" && input.framesTotal > 0 -> Result.FAIL
                else -> Result.WAIT
            },
            "verdict=${input.verdict} sinceChange=${input.msSinceChange}ms diff=${fmt(input.diff)}"
        ),

        // 8 — stash state
        Stage(
            8, "stash",
            when (input.stash) {
                "clean" -> Result.PASS
                "burned" -> Result.WAIT
                else -> if (input.framesTotal > 0) Result.FAIL else Result.WAIT
            },
            when (input.stash) {
                "clean" -> "clean frame stashed — usable for OCR without hiding the overlay"
                "burned" -> "burned (patches on screen when captured) — a clean capture will be awaited"
                else -> "none — no thumbnail yet, the tick cannot advance"
            }
        ),

        // 9 — last OCR
        Stage(
            9, "last OCR",
            when {
                input.ocrAgeMs < 0 -> Result.WAIT
                input.ocrLines > 0 -> Result.PASS
                else -> Result.FAIL
            },
            when {
                input.ocrAgeMs < 0 -> "never run in this session"
                else -> "${input.ocrLines} lines in ${input.ocrMs}ms (${input.ocrAgeMs / 1000}s ago)" +
                    if (input.ocrAgeMs > STALE_RESULT_MS) " · stale" else ""
            }
        ),

        // 10 — last translation
        Stage(
            10, "last translation",
            when {
                input.translateAgeMs < 0 -> Result.WAIT
                input.translateError != null -> Result.FAIL
                input.translateEngine != "none" -> Result.PASS
                else -> Result.FAIL
            },
            when {
                input.translateAgeMs < 0 -> "never run in this session"
                input.translateError != null ->
                    "FAILED ${input.translateAgeMs / 1000}s ago — engine=${input.translateEngine} error=${input.translateError}"
                else -> "engine=${input.translateEngine} ${input.translateMs}ms (${input.translateAgeMs / 1000}s ago)" +
                    if (input.translateAgeMs > STALE_RESULT_MS) " · stale" else ""
            }
        ),

        // 11 — patches painted N of M
        Stage(
            11, "patches painted",
            when {
                input.patchesExpected <= 0 -> Result.WAIT
                input.patchesPainted >= input.patchesExpected -> Result.PASS
                else -> Result.FAIL
            },
            if (input.patchesExpected <= 0) "nothing to paint yet (0 boxes)"
            else "${input.patchesPainted} of ${input.patchesExpected}" +
                if (input.patchesPainted < input.patchesExpected) " — some patch builds failed" else ""
        ),

        // 12 — last patch removal reason
        Stage(
            12, "last patch removal",
            if (input.lastRemoval.startsWith("error")) Result.FAIL else Result.PASS,
            input.lastRemoval.ifBlank { "none" }
        )
    )

    /** `name=value->action` → `name`; tolerates the plain "none" value. */
    fun gateNameOf(lastGate: String): String =
        if (lastGate.isBlank() || lastGate == LiveGates.GATE_NONE) LiveGates.GATE_NONE
        else lastGate.substringBefore('=').trim()

    /** Overall verdict for the header line. */
    fun overall(stages: List<Stage>): Result = when {
        stages.any { it.result == Result.FAIL } -> Result.FAIL
        stages.all { it.result == Result.PASS } -> Result.PASS
        else -> Result.WAIT
    }

    /**
     * Plain-text report for "Copy report": the same 12 stages plus [extraLines] (projection,
     * prefs, driver diagnostics) and the last [logLines] Live log lines. Key-scrubbed.
     */
    fun report(
        versionName: String,
        versionCode: Int,
        input: Input,
        extraLines: List<String>,
        logLines: List<String>,
        nowMs: Long = System.currentTimeMillis()
    ): String {
        val stages = stages(input)
        val sb = StringBuilder()
        sb.append("ManhwaLens Live self-check — v$versionName ($versionCode)\n")
        sb.append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(nowMs))).append('\n')
        sb.append("OVERALL: ").append(overall(stages).wire).append('\n')
        sb.append("— stages —\n")
        for (s in stages) sb.append(s.line()).append('\n')
        if (extraLines.isNotEmpty()) {
            sb.append("— state —\n")
            for (l in extraLines) sb.append(LiveLogRing.scrub(l)).append('\n')
        }
        sb.append("— last ").append(logLines.size).append(" log lines (MLCapture/ManhwaLens) —\n")
        for (l in logLines) sb.append(LiveLogRing.scrub(l)).append('\n')
        return sb.toString().trim()
    }

    fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v)
}
