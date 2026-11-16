package com.manhwalens.app.util

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Opt-in aggregate timing for one Live ON → OFF session.
 *
 * Only durations and counts are retained. OCR and translation text never enters this report.
 */
object LiveSessionBenchmark {
    private const val PREFS = "live_session_benchmark"
    private const val KEY_ARMED = "armed"
    private const val REPORT_FILE = "live-session-benchmark.txt"

    private val lock = Any()
    private var active = false
    private var startedElapsedMs = 0L
    private var startedWallMs = 0L
    private var frames = 0L
    private var passTotalMs = 0L
    private var passMaxMs = 0L
    private var ocrSamples = 0L
    private var ocrTotalMs = 0L
    private var ocrMaxMs = 0L
    private var engineSamples = 0L
    private var engineTotalMs = 0L
    private var engineMaxMs = 0L
    private var outputs = 0L
    private var firstOutputAtMs = -1L

    fun arm(context: Context): Boolean {
        val app = context.applicationContext
        if (com.manhwalens.app.data.SettingsPrefs(app).liveMode) return false
        synchronized(lock) {
            if (active) return false
            prefs(app).edit().putBoolean(KEY_ARMED, true).apply()
            return true
        }
    }

    fun disarm(context: Context) {
        prefs(context.applicationContext).edit().putBoolean(KEY_ARMED, false).apply()
    }

    fun isArmed(context: Context): Boolean =
        prefs(context.applicationContext).getBoolean(KEY_ARMED, false)

    fun onLiveEnabled(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            if (active || !prefs(app).getBoolean(KEY_ARMED, false)) return
            resetCounters()
            startedElapsedMs = SystemClock.elapsedRealtime()
            startedWallMs = System.currentTimeMillis()
            active = true
            prefs(app).edit().putBoolean(KEY_ARMED, false).apply()
        }
    }

    fun onLiveDisabled(context: Context, reason: String) {
        val app = context.applicationContext
        val report = synchronized(lock) {
            if (!active) return
            val now = SystemClock.elapsedRealtime()
            val duration = (now - startedElapsedMs).coerceAtLeast(0L)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(startedWallMs))
            val firstOutput = if (firstOutputAtMs >= 0L) "${firstOutputAtMs} ms" else "not shown"
            val avgPass = if (frames > 0) passTotalMs / frames else 0L
            val avgOcr = if (ocrSamples > 0) ocrTotalMs / ocrSamples else 0L
            val avgEngine = if (engineSamples > 0) engineTotalMs / engineSamples else 0L
            val text = buildString {
                appendLine("ManhwaLens Live session benchmark")
                appendLine("Started: $stamp")
                appendLine("Ended: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                appendLine("Stop reason: ${reason.take(80)}")
                appendLine("Live ON → OFF: $duration ms")
                appendLine("First visible translation: $firstOutput")
                appendLine("Live frames processed: $frames")
                appendLine("Frame processing: avg ${avgPass} ms · max ${passMaxMs} ms")
                appendLine("OCR: avg ${avgOcr} ms · max ${ocrMaxMs} ms · measured frames=$ocrSamples")
                appendLine("Translation engine: avg ${avgEngine} ms · max ${engineMaxMs} ms · measured frames=$engineSamples")
                appendLine("Visible overlay outputs: $outputs")
                appendLine("Privacy: timing and counts only; no page text")
            }.trim()
            active = false
            text
        }
        runCatching { File(app.filesDir, REPORT_FILE).writeText(report) }
    }

    fun recordFrame(passMs: Long, ocrMs: Long, engineMs: Long) {
        synchronized(lock) {
            if (!active) return
            frames++
            val pass = passMs.coerceAtLeast(0L)
            passTotalMs += pass
            if (pass > passMaxMs) passMaxMs = pass
            if (ocrMs >= 0L) {
                ocrSamples++
                ocrTotalMs += ocrMs
                if (ocrMs > ocrMaxMs) ocrMaxMs = ocrMs
            }
            if (engineMs >= 0L) {
                engineSamples++
                engineTotalMs += engineMs
                if (engineMs > engineMaxMs) engineMaxMs = engineMs
            }
        }
    }

    fun recordOutput() {
        synchronized(lock) {
            if (!active) return
            outputs++
            if (firstOutputAtMs < 0L) firstOutputAtMs = (SystemClock.elapsedRealtime() - startedElapsedMs).coerceAtLeast(0L)
        }
    }

    fun readReport(context: Context): String? =
        runCatching { File(context.applicationContext.filesDir, REPORT_FILE).takeIf { it.isFile }?.readText() }.getOrNull()

    private fun resetCounters() {
        frames = 0L
        passTotalMs = 0L
        passMaxMs = 0L
        ocrSamples = 0L
        ocrTotalMs = 0L
        ocrMaxMs = 0L
        engineSamples = 0L
        engineTotalMs = 0L
        engineMaxMs = 0L
        outputs = 0L
        firstOutputAtMs = -1L
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
