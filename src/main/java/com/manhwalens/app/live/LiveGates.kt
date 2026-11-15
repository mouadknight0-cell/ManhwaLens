package com.manhwalens.app.live

/**
 * Pure-JVM gate chain for the Live tick (v0.9.5 · Part 1 — silent-stop audit).
 *
 * WHY: every early return in `liveTickInner()` used to leave the loop silently paused with
 * no trace of WHICH condition stopped it. On a Redmi 13C that is indistinguishable from
 * "Live is broken". This object is the single decision table for the whole gate chain, so
 * the service only has to apply the outcome and so the chain is unit-testable.
 *
 * Two jobs:
 *  1. report the FIRST blocking gate as one line — `live gate: <name> = <value> -> <action>`;
 *  2. SELF-HEAL flags that can stick (app-visible counter with no activity really started,
 *     screen-off while the display is interactive, standby that missed a frame change,
 *     `liveJobRunning`/`captureInFlight` left set by a rejected task or a crash).
 *
 * No Android types.
 */
object LiveGates {

    /** What the tick must do after the gate chain. */
    enum class Outcome {
        /** No gate blocked — run the driver tick. */
        RUN,
        /** Blocked for ~1 s (paused state). */
        PAUSE,
        /** Transient — come back quickly (250/300 ms). */
        WAIT,
        /** Battery standby: the service probes for a frame change at 1 Hz. */
        STANDBY
    }

    /** A stuck flag the service must clear before continuing. */
    data class Heal(val flag: String, val detail: String) {
        fun line(): String = "live heal: $flag — $detail"
    }

    data class Decision(
        val outcome: Outcome,
        /** The first blocking gate, or null when the tick may run. */
        val gate: LiveGate?,
        val delayMs: Long,
        val heals: List<Heal>
    )

    /**
     * Everything the gate chain needs, gathered by the service.
     *
     * @param appVisibleCounter the [com.manhwalens.app.util.AppVisibility] counter
     * @param appActuallyStarted activities the tracker really sees started (ground truth)
     * @param screenInteractive `PowerManager.isInteractive` — ground truth for screenOff
     * @param lastChangeAtMs driver's last observed frame change (−1 = never)
     * @param standbyEnteredAtMs when battery standby was entered (−1 = not in standby)
     */
    data class Input(
        val liveMode: Boolean,
        val projectionReady: Boolean,
        val screenOff: Boolean,
        val screenInteractive: Boolean,
        val appVisibleCounter: Int,
        val appActuallyStarted: Int,
        val standby: Boolean,
        val standbyEnteredAtMs: Long,
        val lastChangeAtMs: Long,
        val foregroundAllowed: Boolean,
        val foregroundDetail: String,
        val foregroundPackage: String?,
        val captureInFlight: Boolean,
        val captureStartedAtMs: Long,
        val liveJobRunning: Boolean,
        val liveJobStartedAtMs: Long,
        val nowMs: Long
    )

    /** Gate names — stable strings used by the self-check screen and the log lines. */
    const val GATE_LIVE_MODE = "liveMode"
    const val GATE_PROJECTION = "projection"
    const val GATE_SCREEN_OFF = "screenOff"
    const val GATE_APP_VISIBLE = "appVisible"
    const val GATE_STANDBY = "standby"
    const val GATE_FOREGROUND = "foregroundApp"
    const val GATE_CAPTURE_IN_FLIGHT = "captureInFlight"
    const val GATE_LIVE_JOB = "liveJobRunning"
    const val GATE_NONE = "none"

    /** Our own package name — Live must never translate our own screens. */
    const val OWN_APP_PACKAGE = "com.manhwalens.app"

    /** A live OCR/translate job older than this is considered stuck and force-cleared. */
    const val LIVE_JOB_WATCHDOG_MS = 20_000L

    /** A tap capture older than this is considered stuck and force-cleared. */
    const val CAPTURE_WATCHDOG_MS = 30_000L

    const val PAUSE_DELAY_MS = 1_000L
    const val WAIT_CAPTURE_DELAY_MS = 250L
    const val WAIT_JOB_DELAY_MS = 300L

    /**
     * Run the chain. Heals are returned (never applied here) so the caller stays in charge
     * of the real flags; the gate line describes the state AFTER healing where relevant.
     */
    fun evaluate(input: Input): Decision {
        val heals = ArrayList<Heal>(3)

        // 1) Live switch ----------------------------------------------------
        if (!input.liveMode) {
            return pause(heals, LiveGate(GATE_LIVE_MODE, "false", "paused (Live is OFF)"))
        }

        // 2) Capture session ------------------------------------------------
        if (!input.projectionReady) {
            return pause(heals, LiveGate(GATE_PROJECTION, "none", "no-session (capture consent needed)"))
        }

        // 3) Screen off — self-heal against the real display state ----------
        if (input.screenOff) {
            if (input.screenInteractive) {
                heals += Heal(GATE_SCREEN_OFF, "counter said off but the display is interactive → resumed")
            } else {
                return pause(heals, LiveGate(GATE_SCREEN_OFF, "true", "paused (screen off)"))
            }
        }

        // 4) Our own UI on screen — self-heal a stuck counter ---------------
        if (input.appVisibleCounter > 0) {
            if (input.appActuallyStarted <= 0) {
                heals += Heal(
                    GATE_APP_VISIBLE,
                    "counter=${input.appVisibleCounter} but 0 activities started → reset to 0"
                )
            } else {
                return pause(
                    heals,
                    LiveGate(
                        GATE_APP_VISIBLE,
                        "counter=${input.appVisibleCounter} started=${input.appActuallyStarted}",
                        "paused (ManhwaLens UI visible)"
                    )
                )
            }
        }

        // 5) Our own app in the foreground — never translate our own screens ----------
        // (Settings, consent trampoline, review, etc.) regardless of the allow-list.
        input.foregroundPackage?.let { pkg ->
            if (pkg.equals(OWN_APP_PACKAGE, ignoreCase = true)) {
                return pause(
                    heals,
                    LiveGate(GATE_APP_VISIBLE, "foreground=$pkg", "paused (own app visible)")
                )
            }
        }

        // 6) Battery standby — leave it when a change happened while asleep --
        if (input.standby) {
            val missedChange = input.standbyEnteredAtMs > 0 &&
                input.lastChangeAtMs > input.standbyEnteredAtMs
            if (missedChange) {
                heals += Heal(
                    GATE_STANDBY,
                    "frame changed at ${input.lastChangeAtMs} (standby since ${input.standbyEnteredAtMs}) → resumed"
                )
            } else {
                val age = if (input.standbyEnteredAtMs > 0) input.nowMs - input.standbyEnteredAtMs else -1L
                return Decision(
                    Outcome.STANDBY,
                    LiveGate(GATE_STANDBY, "true (${age}ms)", "standby (battery) — probing at 1 Hz"),
                    PAUSE_DELAY_MS,
                    heals
                )
            }
        }

        // 6) Foreground allow-list -----------------------------------------
        if (!input.foregroundAllowed) {
            return pause(
                heals,
                LiveGate(GATE_FOREGROUND, input.foregroundDetail, "paused (app not in allow-list)")
            )
        }

        // 7) A tap capture owns the mirror right now ------------------------
        if (input.captureInFlight) {
            val age = if (input.captureStartedAtMs > 0) input.nowMs - input.captureStartedAtMs else -1L
            if (age > CAPTURE_WATCHDOG_MS) {
                heals += Heal(GATE_CAPTURE_IN_FLIGHT, "stuck for ${age}ms (> ${CAPTURE_WATCHDOG_MS}ms) → cleared")
            } else {
                return Decision(
                    Outcome.WAIT,
                    LiveGate(GATE_CAPTURE_IN_FLIGHT, "true (${age}ms)", "wait (tap capture in flight)"),
                    WAIT_CAPTURE_DELAY_MS,
                    heals
                )
            }
        }

        // 8) One live job at a time ----------------------------------------
        if (input.liveJobRunning) {
            val age = if (input.liveJobStartedAtMs > 0) input.nowMs - input.liveJobStartedAtMs else -1L
            if (age > LIVE_JOB_WATCHDOG_MS) {
                heals += Heal(GATE_LIVE_JOB, "stuck for ${age}ms (> ${LIVE_JOB_WATCHDOG_MS}ms) → cleared")
            } else {
                return Decision(
                    Outcome.WAIT,
                    LiveGate(GATE_LIVE_JOB, "true (${age}ms)", "wait (live job running)"),
                    WAIT_JOB_DELAY_MS,
                    heals
                )
            }
        }

        return Decision(Outcome.RUN, null, 0L, heals)
    }

    private fun pause(heals: List<Heal>, gate: LiveGate) =
        Decision(Outcome.PAUSE, gate, PAUSE_DELAY_MS, heals)

    /**
     * Is a gate benign (transient) or a real stop? Used by the self-check screen to colour
     * stage 6 and by the status line.
     */
    fun severity(gateName: String?): Severity = when (gateName) {
        null, GATE_NONE -> Severity.OK
        GATE_LIVE_MODE, GATE_PROJECTION, GATE_APP_VISIBLE, GATE_FOREGROUND -> Severity.BLOCKED
        GATE_SCREEN_OFF, GATE_STANDBY, GATE_CAPTURE_IN_FLIGHT, GATE_LIVE_JOB -> Severity.WAITING
        else -> Severity.BLOCKED
    }

    enum class Severity { OK, WAITING, BLOCKED }
}

/** One gate decision, rendered as `live gate: <name> = <value> -> <action>`. */
data class LiveGate(val name: String, val value: String, val action: String) {
    fun line(): String = "live gate: $name = $value -> $action"

    /** Compact form for the status line / self-check (no spaces, log-friendly). */
    fun wire(): String = "$name=$value->$action"
}

/**
 * Rate limiter for gate lines: the same gate may be hit 3×/second, but at most one line per
 * [intervalMs] per gate name reaches the log. [lastGate] is ALWAYS updated (the status line
 * and the self-check screen must show the current gate even when the log stayed quiet).
 */
class LiveGateLog(
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    private val lastLoggedAtMs = HashMap<String, Long>()

    /** The most recent gate (null = the tick ran through with no gate). */
    var lastGate: LiveGate? = null
        private set

    var lastGateAtMs: Long = 0L
        private set

    /** How many times each gate blocked since [reset] (self-check + report). */
    private val hits = HashMap<String, Int>()

    /**
     * Record [gate] (or null when nothing blocked).
     * @return true when the caller should emit the log line (rate limit allows it).
     */
    fun record(gate: LiveGate?): Boolean {
        val now = clock()
        lastGate = gate
        lastGateAtMs = now
        if (gate == null) return false
        hits[gate.name] = (hits[gate.name] ?: 0) + 1
        val last = lastLoggedAtMs[gate.name] ?: Long.MIN_VALUE
        if (now - last < intervalMs) return false
        lastLoggedAtMs[gate.name] = now
        return true
    }

    fun hitCount(name: String): Int = hits[name] ?: 0

    fun counts(): Map<String, Int> = HashMap(hits)

    fun reset() {
        lastLoggedAtMs.clear()
        hits.clear()
        lastGate = null
        lastGateAtMs = 0L
    }

    /** `gate1=N gate2=M …` for the report. */
    fun summary(): String =
        if (hits.isEmpty()) "none" else hits.entries.sortedByDescending { it.value }
            .joinToString(" ") { "${it.key}=${it.value}" }

    companion object {
        /** "rate-limited to once per second per gate". */
        const val DEFAULT_INTERVAL_MS = 1_000L
    }
}
