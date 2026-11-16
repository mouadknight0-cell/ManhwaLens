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
    private var firstFrameStartElapsedMs = -1L
    private var firstFrameStartedAtOffsetMs = -1L
    private var firstFramePassMs = -1L
    private var firstFrameOcrMs = -1L
    private var firstFrameEngineMs = -1L
    private var firstOutputPostFrameMs = -1L
    private var detectorSamples = 0L
    private var detectorTotalMs = 0L
    private var detectorMaxMs = 0L
    private var detectorInferenceTotalMs = 0L
    private var detectorInferenceMaxMs = 0L
    private var recognizerSamples = 0L
    private var recognizerTotalMs = 0L
    private var recognizerMaxMs = 0L
    private var recognizerInferenceTotalMs = 0L
    private var recognizerInferenceMaxMs = 0L
    private var coldLoadMaxMs = 0L
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
            val firstFrameStart = if (firstFrameStartedAtOffsetMs >= 0L) "${firstFrameStartedAtOffsetMs} ms" else "no frame entered"
            val firstFrameDuration = if (firstFramePassMs >= 0L) "${firstFramePassMs} ms" else "n/a"
            val firstFrameOcr = if (firstFrameOcrMs >= 0L) "${firstFrameOcrMs} ms" else "n/a"
            val firstFrameEngine = if (firstFrameEngineMs >= 0L) "${firstFrameEngineMs} ms" else "n/a"
            val firstOutputAfterFrame = if (firstOutputPostFrameMs >= 0L) "${firstOutputPostFrameMs} ms" else "n/a"
            val avgDetector = if (detectorSamples > 0) detectorTotalMs / detectorSamples else 0L
            val avgDetectorInference = if (detectorSamples > 0) detectorInferenceTotalMs / detectorSamples else 0L
            val avgRecognizer = if (recognizerSamples > 0) recognizerTotalMs / recognizerSamples else 0L
            val avgRecognizerInference = if (recognizerSamples > 0) recognizerInferenceTotalMs / recognizerSamples else 0L
            val avgPass = if (frames > 0) passTotalMs / frames else 0L
            val avgOcr = if (ocrSamples > 0) ocrTotalMs / ocrSamples else 0L
            val avgEngine = if (engineSamples > 0) engineTotalMs / engineSamples else 0L
            val text = buildString {
                appendLine("ManhwaLens Live session benchmark")
                appendLine("Started: $stamp")
                appendLine("Ended: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                appendLine("Stop reason: ${reason.take(80)}")
                appendLine("Live ON → OFF: $duration ms")
                appendLine("First Live frame entered: $firstFrameStart")
                appendLine("First frame work: total=$firstFrameDuration · OCR=$firstFrameOcr · engine=$firstFrameEngine")
                appendLine("First visible translation: $firstOutput")
                appendLine("Delay outside first frame work: $firstOutputAfterFrame")
                appendLine("Live frames processed: $frames")
                appendLine("Frame processing: avg ${avgPass} ms · max ${passMaxMs} ms")
                appendLine("OCR total: avg ${avgOcr} ms · max ${ocrMaxMs} ms · measured frames=$ocrSamples")
                appendLine("Detector: avg ${avgDetector} ms · inference avg/max ${avgDetectorInference}/${detectorInferenceMaxMs} ms · max ${detectorMaxMs} ms · measured=$detectorSamples")
                appendLine("Recognizer: avg ${avgRecognizer} ms · inference avg/max ${avgRecognizerInference}/${recognizerInferenceMaxMs} ms · max ${recognizerMaxMs} ms · measured=$recognizerSamples")
                appendLine("OCR cold model load max: ${coldLoadMaxMs} ms")
                appendLine("Translation engine: avg ${avgEngine} ms · max ${engineMaxMs} ms · measured frames=$engineSamples")
                appendLine("Visible overlay outputs: $outputs")
                appendLine("Privacy: timing and counts only; no page text")
            }.trim()
            active = false
            text
        }
        runCatching { File(app.filesDir, REPORT_FILE).writeText(report) }
    }

    fun recordFrameStarted(startedAtElapsedMs: Long) {
        synchronized(lock) {
            if (!active || firstFrameStartElapsedMs >= 0L) return
            firstFrameStartElapsedMs = startedAtElapsedMs
            firstFrameStartedAtOffsetMs = (startedAtElapsedMs - startedElapsedMs).coerceAtLeast(0L)
        }
    }

    fun recordOcrStats(stats: com.manhwalens.app.ocr.OcrRunStats?) {
        if (stats == null) return
        synchronized(lock) {
            if (!active) return
            detectorSamples++
            detectorTotalMs += stats.detectionTimeMs.coerceAtLeast(0L)
            detectorInferenceTotalMs += stats.detectionInferenceMs.coerceAtLeast(0L)
            detectorMaxMs = maxOf(detectorMaxMs, stats.detectionTimeMs)
            detectorInferenceMaxMs = maxOf(detectorInferenceMaxMs, stats.detectionInferenceMs)
            recognizerSamples++
            recognizerTotalMs += stats.recognitionTimeMs.coerceAtLeast(0L)
            recognizerInferenceTotalMs += stats.recognitionInferenceMs.coerceAtLeast(0L)
            recognizerMaxMs = maxOf(recognizerMaxMs, stats.recognitionTimeMs)
            recognizerInferenceMaxMs = maxOf(recognizerInferenceMaxMs, stats.recognitionInferenceMs)
            coldLoadMaxMs = maxOf(coldLoadMaxMs, stats.coldLoadTimeMs)
        }
    }

    fun recordFrame(passMs: Long, ocrMs: Long, engineMs: Long) {
        synchronized(lock) {
            if (!active) return
            if (frames == 0L) {
                firstFramePassMs = passMs.coerceAtLeast(0L)
                firstFrameOcrMs = ocrMs
                firstFrameEngineMs = engineMs
                if (firstOutputAtMs >= 0L) {
                    firstOutputPostFrameMs = (firstOutputAtMs - firstFrameStartedAtOffsetMs - firstFramePassMs).coerceAtLeast(0L)
                }
            }
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
            if (firstOutputAtMs < 0L) {
                firstOutputAtMs = (SystemClock.elapsedRealtime() - startedElapsedMs).coerceAtLeast(0L)
                if (firstFramePassMs >= 0L && firstFrameStartedAtOffsetMs >= 0L) {
                    firstOutputPostFrameMs = (firstOutputAtMs - firstFrameStartedAtOffsetMs - firstFramePassMs).coerceAtLeast(0L)
                }
            }
        }
    }

    fun readReport(context: Context): String? =
        runCatching { File(context.applicationContext.filesDir, REPORT_FILE).takeIf { it.isFile }?.readText() }.getOrNull()

    private fun resetCounters() {
        frames = 0L
        passTotalMs = 0L
        passMaxMs = 0L
        firstFrameStartElapsedMs = -1L
        firstFrameStartedAtOffsetMs = -1L
        firstFramePassMs = -1L
        firstFrameOcrMs = -1L
        firstFrameEngineMs = -1L
        firstOutputPostFrameMs = -1L
        detectorSamples = 0L
        detectorTotalMs = 0L
        detectorMaxMs = 0L
        detectorInferenceTotalMs = 0L
        detectorInferenceMaxMs = 0L
        recognizerSamples = 0L
        recognizerTotalMs = 0L
        recognizerMaxMs = 0L
        recognizerInferenceTotalMs = 0L
        recognizerInferenceMaxMs = 0L
        coldLoadMaxMs = 0L
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
