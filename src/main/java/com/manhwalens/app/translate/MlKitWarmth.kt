package com.manhwalens.app.translate

/**
 * Cold-vs-warm bookkeeping for the on-device translator (v0.9.9). The first dummy call after
 * process start pays for loading the EN→AR model into memory ("cold"); later calls are "warm".
 * Pure, process-wide; the service warms the engine at start and on every screen-on.
 */
object MlKitWarmth {
    private val lock = Any()
    private var coldMs = -1L
    private var coldReason = ""
    private var lastWarmMs = -1L
    private var lastWarmAt = 0L
    private var lastReason = ""
    private var warmUps = 0

    /** Record one dummy-call timing. The first one ever is the cold number. */
    fun note(durationMs: Long, reason: String, nowMs: Long) = synchronized(lock) {
        if (coldMs < 0) {
            coldMs = durationMs
            coldReason = reason
        } else {
            lastWarmMs = durationMs
            lastReason = reason
        }
        lastWarmAt = nowMs
        warmUps++
    }

    fun isWarm(): Boolean = synchronized(lock) { coldMs >= 0 }

    /** Ms since the last dummy call (Long.MAX_VALUE when none ran). */
    fun ageMs(nowMs: Long): Long = synchronized(lock) {
        if (coldMs < 0) Long.MAX_VALUE else (nowMs - lastWarmAt).coerceAtLeast(0L)
    }

    /** One line for the pipeline trace. */
    fun describe(nowMs: Long): String = synchronized(lock) {
        if (coldMs < 0) return "NOT pre-warmed — this call pays the cold start"
        val age = ((nowMs - lastWarmAt).coerceAtLeast(0L)) / 1000
        val warm = if (lastWarmMs >= 0) "warm ${lastWarmMs} ms ($lastReason)" else "warm n/a (no second warm-up yet)"
        "cold ${coldMs} ms ($coldReason) · $warm · last warm-up ${age}s ago · warm-ups $warmUps"
    }

    fun resetForTests() = synchronized(lock) {
        coldMs = -1L; coldReason = ""; lastWarmMs = -1L; lastWarmAt = 0L; lastReason = ""; warmUps = 0
    }
}
