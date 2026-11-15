package com.manhwalens.app.util

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Local, best-effort diagnostics. The event history is bounded and survives
 * process restarts; no screenshots, OCR text, translations, or network payloads
 * are collected by this logger.
 */
object ErrorLog {

    private const val FILE_NAME = "manhwalens-last-error.txt"
    private const val EVENTS_FILE_NAME = "manhwalens-events.log"
    private const val MAX_ERROR_CHARS = 16_000
    private const val MAX_EVENT_CHARS = 32_000
    private const val MAX_EVENT_DETAIL = 700

    @Volatile
    private var installed = false

    /** Install a process-wide uncaught exception handler as early as possible. */
    fun install(context: Context) {
        if (installed) return
        synchronized(this) {
            if (installed) return
            val appContext = context.applicationContext
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    Log.e("ManhwaLens", "uncaught:${thread.name} — ${throwable.javaClass.name}: ${throwable.message}", throwable)
                    write(appContext, "uncaught:${thread.name}", throwable)
                } catch (_: Throwable) {
                    // Never recurse into a failing logger.
                }
                defaultHandler?.uncaughtException(thread, throwable)
            }
            installed = true
            record(appContext, "diagnostics", "uncaught-exception handler installed")
        }
    }

    /** Add one compact event to the persistent bounded history. */
    fun record(context: Context, source: String, detail: String) {
        try {
            val ts = timestamp()
            val safeSource = sanitize(source).replace('\n', ' ').take(80)
            val safeDetail = sanitize(detail).replace('\n', ' ').replace('\r', ' ').take(MAX_EVENT_DETAIL)
            val line = "[$ts] [$safeSource] $safeDetail\n"
            synchronized(this) {
                val file = File(context.filesDir, EVENTS_FILE_NAME)
                val previous = if (file.exists()) file.readText() else ""
                val combined = previous + line
                file.writeText(if (combined.length > MAX_EVENT_CHARS) combined.takeLast(MAX_EVENT_CHARS) else combined)
            }
        } catch (_: Throwable) {
            // Diagnostics must never destabilize the app.
        }
    }

    /** Persist a fatal or explicitly reported exception and retain it as the last error. */
    fun write(context: Context, source: String, throwable: Throwable) {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val text = "[${timestamp()}] [$source]\n" +
                "${throwable.javaClass.name}: ${throwable.message}\n" +
                sanitize(sw.toString())
            File(context.filesDir, FILE_NAME).writeText(text.take(MAX_ERROR_CHARS))
            record(context, source, "${throwable.javaClass.simpleName}: ${throwable.message}")
        } catch (_: Throwable) {
            // Best effort only.
        }
    }

    /**
     * Returns the last error plus recent startup/runtime events. Existing UI
     * surfaces that already copy this value automatically export both sections.
     */
    fun read(context: Context): String? = try {
        val error = File(context.filesDir, FILE_NAME)
            .takeIf { it.exists() && it.length() > 0L }
            ?.readText()
        val events = readEvents(context)
        when {
            error.isNullOrBlank() && events.isNullOrBlank() -> null
            error.isNullOrBlank() -> events
            events.isNullOrBlank() -> error
            else -> "$error\n\n===== RECENT EVENT HISTORY =====\n$events"
        }
    } catch (_: Throwable) {
        null
    }

    fun readLastError(context: Context): String? = try {
        File(context.filesDir, FILE_NAME)
            .takeIf { it.exists() && it.length() > 0L }
            ?.readText()
    } catch (_: Throwable) {
        null
    }

    fun readEvents(context: Context): String? = try {
        File(context.filesDir, EVENTS_FILE_NAME)
            .takeIf { it.exists() && it.length() > 0L }
            ?.readText()
    } catch (_: Throwable) {
        null
    }

    /** Store a structured pipeline report and add a short event-history entry. */
    fun writeRaw(context: Context, source: String, text: String) {
        try {
            val safe = sanitize(text).take(MAX_ERROR_CHARS)
            File(context.filesDir, FILE_NAME).writeText("[${timestamp()}] [$source]\n$safe\n")
            record(context, source, "diagnostic report updated (${safe.length} chars)")
        } catch (_: Throwable) {
            // Best effort only.
        }
    }

    /** Compact one-line-ish reason for toasts: "SecurityException: msg". */
    fun reasonOf(throwable: Throwable): String =
        throwable.javaClass.simpleName + (throwable.message?.let { ": $it" } ?: "")

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private fun sanitize(value: String): String =
        value.replace(Regex("AIza[0-9A-Za-z_-]{10,}"), "[redacted]")
            .replace(Regex("(?i)(api[-_ ]?key|key)\\s*[=:]\\s*\\S+"), "api_key=[redacted]")
}
