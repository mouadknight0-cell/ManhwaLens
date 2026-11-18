package com.manhwalens.app.util

import java.io.File

/**
 * Crash-loop guard state machine (pure JVM — unit-testable with temp files).
 *
 * A tiny file in the app's private filesDir holds the number of CONSECUTIVE
 * startups that did not survive 5 seconds. [ManhwaLensApp] increments it at
 * process start; a posted callback clears it after 5 s of healthy runtime.
 *
 * If the app died at startup twice in a row, the next launch starts in
 * Safe Mode: no floating-button service, no Live loop, default (safe)
 * behavior — Settings and the crash banner still work so the user can read
 * and copy the saved stack trace.
 *
 * All operations are best-effort and never throw — a broken counter file is
 * treated as 0.
 */
class StartupGuard(private val counterFile: File) {

    private val exceptionFile = File(counterFile.parentFile, counterFile.name + ".last_exception")

    fun lastExceptionSummary(): String = try {
        exceptionFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
    } catch (_: Throwable) { "" }

    fun recordExceptionSummary(throwable: Throwable) {
        try {
            val frames = throwable.stackTrace.take(4).joinToString(" <- ") { frame ->
                "${frame.className}.${frame.methodName}(${frame.fileName ?: "?"}:${frame.lineNumber})"
            }
            val message = throwable.message.orEmpty().replace(Regex("\\s+"), " ").take(240)
            exceptionFile.parentFile?.mkdirs()
            exceptionFile.writeText("${throwable.javaClass.name}: $message | $frames")
        } catch (_: Throwable) { }
    }

    fun reportLine(): String {
        val count = currentCount()
        val last = lastExceptionSummary().ifBlank { "none" }.replace(Regex("[\\r\\n]+"), " ").take(500)
        return "consecutive=$count safe_mode=${isSafeMode(count)} last_exception=$last"
    }

    fun currentCount(): Int = try {
        counterFile.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
    } catch (_: Throwable) {
        0
    }

    /** Register this process start; returns the consecutive unhealthy-start count (1-based). */
    fun registerStart(): Int = try {
        val next = currentCount() + 1
        counterFile.parentFile?.mkdirs()
        counterFile.writeText(next.toString())
        next
    } catch (_: Throwable) {
        // Filesystem trouble must never block launch; stay neutral.
        1
    }

    /** Called after 5 s of healthy running: the crash streak is over. */
    fun markHealthy() {
        try {
            if (counterFile.exists()) counterFile.delete()
        } catch (_: Throwable) {
            runCatching { counterFile.writeText("0") }
        }
    }

    companion object {
        const val FILE_NAME = "startup_crash_counter"

        /** Two unhealthy startups in a row → Safe Mode on the next launch. */
        const val SAFE_MODE_THRESHOLD = 2

        fun isSafeMode(consecutiveUnhealthyStarts: Int): Boolean =
            consecutiveUnhealthyStarts >= SAFE_MODE_THRESHOLD
    }
}

/**
 * Process-global Safe Mode flag. Set at attach time by [com.manhwalens.app.ManhwaLensApp]
 * (two consecutive unhealthy starts); cleared by the Safe Mode banner button in MainActivity
 * (which also resets the crash streak via [StartupGuard.markHealthy]).
 */
object SafeMode {
    @Volatile
    var active: Boolean = false
        internal set
}
