package com.manhwalens.app.util

import java.io.File

/**
 * Crash-loop guard state machine (pure JVM — unit-testable with temp files).
 *
 * A tiny file in the app's private filesDir holds consecutive runs with a
 * recorded uncaught exception. Ordinary short-lived runs do not increment it.
 * [ManhwaLensApp] registers each start; [ErrorLog]'s uncaught handler records
 * the exception and increments the streak exactly once for that run.
 *
 * After three recorded crashes, the next launch starts in Safe Mode. A healthy
 * five-second run or the in-app reset clears the streak.
 *
 * All operations are best-effort and never throw — a broken counter file is
 * treated as 0.
 */
class StartupGuard(private val counterFile: File) {

    private val exceptionFile = File(counterFile.parentFile, counterFile.name + ".last_exception")
    private val runStateFile = File(counterFile.parentFile, counterFile.name + ".run_state")

    private companion object {
        const val RUN_STATE_RUNNING = "v2:running"
        const val RUN_STATE_FATAL = "v2:fatal"
    }

    fun lastExceptionSummary(): String = try {
        exceptionFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
    } catch (_: Throwable) { "" }

    @Synchronized
    fun recordExceptionSummary(throwable: Throwable) {
        try {
            val frames = throwable.stackTrace.take(4).joinToString(" <- ") { frame ->
                "${frame.className}.${frame.methodName}(${frame.fileName ?: "?"}:${frame.lineNumber})"
            }
            val message = throwable.message.orEmpty().replace(Regex("\\s+"), " ").take(240)
            exceptionFile.parentFile?.mkdirs()
            exceptionFile.writeText("${throwable.javaClass.name}: $message | $frames")
            // The handler calls this once for the process-fatal exception.
            // Persist the count and fatal marker so the next launch can safely
            // enter Safe Mode even if the process dies immediately afterward.
            if (runStateFile.takeIf { it.exists() }?.readText()?.trim() != RUN_STATE_FATAL) {
                val previousCount = if (runStateFile.takeIf { it.exists() }?.readText()?.trim() in
                    setOf(RUN_STATE_RUNNING, RUN_STATE_FATAL)
                ) currentCount() else 0 // discard counters written by the old 5-second policy
                counterFile.parentFile?.mkdirs()
                counterFile.writeText((previousCount + 1).toString())
                runStateFile.writeText(RUN_STATE_FATAL)
            }
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

    /** Register this process start without treating an ordinary exit as a crash. */
    @Synchronized
    fun registerStart(): Int = try {
        counterFile.parentFile?.mkdirs()
        val state = runStateFile.takeIf { it.exists() }?.readText()?.trim()
        if (state != RUN_STATE_RUNNING && state != RUN_STATE_FATAL) {
            // Migrate away from counts produced by the old short-run policy.
            runCatching { counterFile.delete() }
        }
        runStateFile.writeText(RUN_STATE_RUNNING)
        currentCount()
    } catch (_: Throwable) {
        // Filesystem trouble must never block launch or invent a crash.
        currentCount()
    }

    /** Called after 5 s of healthy running or by the user's reset button. */
    @Synchronized
    fun markHealthy() {
        try {
            if (counterFile.exists()) counterFile.delete()
            runStateFile.parentFile?.mkdirs()
            runStateFile.writeText(RUN_STATE_RUNNING)
        } catch (_: Throwable) {
            runCatching { counterFile.writeText("0") }
        }
    }

    companion object {
        const val FILE_NAME = "startup_crash_counter"

        /** Three recorded fatal runs → Safe Mode. */
        const val SAFE_MODE_THRESHOLD = 3

        fun isSafeMode(consecutiveFatalRuns: Int): Boolean =
            consecutiveFatalRuns >= SAFE_MODE_THRESHOLD
    }
}

/**
 * Process-global Safe Mode flag. Set at attach time by [com.manhwalens.app.ManhwaLensApp]
 * (three recorded fatal runs); cleared by the Safe Mode reset button in MainActivity
 * (which also resets the crash streak via [StartupGuard.markHealthy]).
 */
object SafeMode {
    @Volatile
    var active: Boolean = false
        internal set
}
