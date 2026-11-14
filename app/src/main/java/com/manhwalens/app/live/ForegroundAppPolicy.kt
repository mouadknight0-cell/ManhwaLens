package com.manhwalens.app.live

/**
 * Pure-JVM allow-list policy for Live mode (v0.9.5 · Part 1b).
 *
 * WHY: `foregroundAppAllowed()` could leave Live blocked forever with a non-empty allow-list
 * when usage-access is granted but the event window is stale (HyperOS/Brave emit no new
 * ACTIVITY_RESUMED while the user only scrolls), because the last known package stayed
 * "not in the list". Rule now: **an unknown or stale foreground app NEVER blocks Live** —
 * only a FRESH event naming a package that is NOT in the list does.
 *
 * The default allow-list is empty (= everything allowed); it is only non-empty when the user
 * typed one in Settings → Live mode → "Only in these apps".
 */
object ForegroundAppPolicy {

    /** A foreground event older than this is stale → treated as "unknown" → allowed. */
    const val STALE_EVENT_MS = 10_000L

    /**
     * How far back the service queries UsageEvents. v0.9.4 used 60 s, which is shorter than
     * the time a reader can stay in the foreground without emitting a new RESUMED event;
     * 5 minutes finds the last real transition without scanning much data.
     */
    const val QUERY_WINDOW_MS = 5 * 60_000L

    data class Decision(
        val allowed: Boolean,
        /** Stable reason string for the gate line / self-check (never contains user text). */
        val reason: String
    )

    /** Parsed, trimmed, lowercased, non-empty package names. */
    fun parseList(allowListRaw: String?): List<String> =
        (allowListRaw ?: "").split(',', '\n', ';')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

    /**
     * @param usageAccessGranted false when the appops check is not MODE_ALLOWED (or threw)
     * @param lastResumedPackage newest ACTIVITY_RESUMED/MOVE_TO_FOREGROUND package, null = none
     * @param lastEventAgeMs age of that event in ms (−1 when there is none)
     */
    fun decide(
        allowListRaw: String?,
        usageAccessGranted: Boolean,
        lastResumedPackage: String?,
        lastEventAgeMs: Long,
        staleAfterMs: Long = STALE_EVENT_MS
    ): Decision {
        val wanted = parseList(allowListRaw)
        if (wanted.isEmpty()) {
            return Decision(true, "empty allow-list (everything allowed)")
        }
        if (!usageAccessGranted) {
            // Cannot check → must not break Live (the list is an opt-in refinement).
            return Decision(true, "no usage access — cannot check, allowing (list=${wanted.size})")
        }
        val pkg = lastResumedPackage?.lowercase()
        if (pkg == null) {
            return Decision(true, "no resumed event in the ${QUERY_WINDOW_MS / 1000}s window — allowing")
        }
        if (lastEventAgeMs >= 0 && lastEventAgeMs > staleAfterMs) {
            return Decision(true, "stale foreground event (${lastEventAgeMs}ms > ${staleAfterMs}ms) — allowing")
        }
        return if (wanted.any { it == pkg }) {
            Decision(true, "foreground=$pkg in list")
        } else {
            Decision(false, "foreground=$pkg not in list(${wanted.joinToString("|")})")
        }
    }
}
