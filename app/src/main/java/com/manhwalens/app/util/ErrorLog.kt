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
 * Persists the last crash/failure into app storage so the user can read (and
 * copy) it from Settings → "Last error". Everything here is best-effort: a
 * failure to log must never take the app down with it.
 */
object ErrorLog {

    private const val FILE_NAME = "manhwalens-last-error.txt"
    private const val MAX_CHARS = 8000

    @Volatile
    private var installed = false

    /** Install a process-wide uncaught exception handler. Idempotent; call once from Application.attachBaseContext()/onCreate(). */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("ManhwaLens", "uncaught:${thread.name} — ${throwable.javaClass.name}: ${throwable.message}", throwable)
                write(appContext, "uncaught:${thread.name}", throwable)
            } catch (_: Throwable) {
                // never recurse into a failing logger
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /** Append-free single-slot write: each new error replaces the previous one. */
    fun write(context: Context, source: String, throwable: Throwable) {
        try {
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val text = "[$ts] [$source]\n" +
                "${throwable.javaClass.name}: ${throwable.message}\n" +
                sw.toString()
            File(context.filesDir, FILE_NAME).writeText(text.take(MAX_CHARS * 4))
        } catch (_: Throwable) {
            // best effort only
        }
    }

    /** Full contents of the last error, or null when nothing was recorded. */
    fun read(context: Context): String? = try {
        File(context.filesDir, FILE_NAME)
            .takeIf { it.exists() && it.length() > 0L }
            ?.readText()
    } catch (_: Throwable) {
        null
    }

    /** Write raw diagnostic text (e.g. the pipeline trace) into the same file. */
    fun writeRaw(context: Context, source: String, text: String) {
        try {
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            File(context.filesDir, FILE_NAME).writeText("[$ts] [$source]\n" + text + "\n")
        } catch (_: Throwable) {
            // best effort only
        }
    }

    /** Compact one-line-ish reason for toasts: "SecurityException: msg". */
    fun reasonOf(throwable: Throwable): String =
        throwable.javaClass.simpleName + (throwable.message?.let { ": $it" } ?: "")
}
