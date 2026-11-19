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
    private var firstOverlayStageTimes: LiveOverlayStageTimes? = null
    private var detectorSamples = 0L
    private var detectorTotalMs = 0L
    private var detectorMaxMs = 0L
    private var detectorInferenceTotalMs = 0L
    private var detectorInferenceMaxMs = 0L
    private var detectorPreprocessTotalMs = 0L
    private var detectorPostprocessTotalMs = 0L
    private var recognizerSamples = 0L
    private var recognizerTotalMs = 0L
    private var recognizerMaxMs = 0L
    private var recognizerInferenceTotalMs = 0L
    private var recognizerInferenceMaxMs = 0L
    private var recognizerPreprocessTotalMs = 0L
    private var recognizerPostprocessTotalMs = 0L
    private var pipelineOverheadTotalMs = 0L
    private var coldLoadMaxMs = 0L
    private var ocrSamples = 0L
    private var ocrTotalMs = 0L
    private var ocrMaxMs = 0L
    private var engineSamples = 0L
    private var engineTotalMs = 0L
    private var engineMaxMs = 0L
    private var outputs = 0L
    private var firstOutputAtMs = -1L
    private var blackFrames = 0L
    private var emptyOutputFrames = 0L
    private var failedPasses = 0L
    private var cancelledPasses = 0L
    private var ocrLines = 0L
    private var ocrCandidates = 0L
    private var lowConfidenceDrops = 0L
    private var translationCalls = 0L
    private var translationSuccesses = 0L
    private var translationFailures = 0L
    private var translationBlocks = 0L
    private var cacheHits = 0L
    private var cacheMisses = 0L
    private var overlayExpected = 0L
    private var overlayPainted = 0L
    private var overlayFailures = 0L
    private var lastOcrEngine = "n/a"
    private var lastEngine = "n/a"
    private var lastOcrConfig = "n/a"
    private var frameWidth = 0
    private var frameHeight = 0
    private val liveStatuses = linkedMapOf<String, Long>()

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
            val firstOverlayStages = firstOverlayStageTimes?.toReportLine() ?: "n/a"
            val avgDetector = if (detectorSamples > 0) detectorTotalMs / detectorSamples else 0L
            val avgDetectorInference = if (detectorSamples > 0) detectorInferenceTotalMs / detectorSamples else 0L
            val avgRecognizer = if (recognizerSamples > 0) recognizerTotalMs / recognizerSamples else 0L
            val avgRecognizerInference = if (recognizerSamples > 0) recognizerInferenceTotalMs / recognizerSamples else 0L
            val avgDetectorPreprocess = if (detectorSamples > 0) detectorPreprocessTotalMs / detectorSamples else 0L
            val avgDetectorPostprocess = if (detectorSamples > 0) detectorPostprocessTotalMs / detectorSamples else 0L
            val avgRecognizerPreprocess = if (recognizerSamples > 0) recognizerPreprocessTotalMs / recognizerSamples else 0L
            val avgRecognizerPostprocess = if (recognizerSamples > 0) recognizerPostprocessTotalMs / recognizerSamples else 0L
            val avgPipelineOverhead = if (recognizerSamples > 0) pipelineOverheadTotalMs / recognizerSamples else 0L
            val avgPass = if (frames > 0) passTotalMs / frames else 0L
            val avgOcr = if (ocrSamples > 0) ocrTotalMs / ocrSamples else 0L
            val avgEngine = if (engineSamples > 0) engineTotalMs / engineSamples else 0L
            val device = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
            val appVersion = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "unknown" }.getOrDefault("unknown")
            val text = buildString {
                appendLine("ManhwaLens Live session benchmark")
                appendLine("Started: $stamp")
                appendLine("Ended: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                appendLine("Stop reason: ${reason.take(80)}")
                appendLine("Live ON → OFF: $duration ms")
                appendLine("Device/app: $device · Android ${android.os.Build.VERSION.SDK_INT} · ManhwaLens $appVersion")
                appendLine("Startup guard: " + StartupGuard(File(app.filesDir, StartupGuard.FILE_NAME)).reportLine())
                appendLine("Captured frame size: ${frameWidth}x${frameHeight}")
                appendLine("Frame outcomes: black=$blackFrames · empty/no patches=$emptyOutputFrames · failed=$failedPasses · cancelled=$cancelledPasses")
                appendLine("OCR output: lines=$ocrLines · detector candidates=$ocrCandidates · low-confidence drops=$lowConfidenceDrops · last engine=$lastOcrEngine")
                appendLine("OCR config (last): $lastOcrConfig")
                appendLine("First Live frame entered: $firstFrameStart")
                appendLine("First frame work: total=$firstFrameDuration · OCR=$firstFrameOcr · engine=$firstFrameEngine")
                appendLine("First visible translation: $firstOutput")
                appendLine("Delay outside first frame work: $firstOutputAfterFrame")
                appendLine("First overlay stages: $firstOverlayStages")
                appendLine("Live frames processed: $frames")
                appendLine("Frame processing: avg ${avgPass} ms · max ${passMaxMs} ms")
                appendLine("OCR total: avg ${avgOcr} ms · max ${ocrMaxMs} ms · measured frames=$ocrSamples")
                appendLine("Detector: avg ${avgDetector} ms · preprocess/inference/postprocess avg ${avgDetectorPreprocess}/${avgDetectorInference}/${avgDetectorPostprocess} ms · inference max ${detectorInferenceMaxMs} ms · max ${detectorMaxMs} ms · measured=${detectorSamples}")
                appendLine("Recognizer: avg ${avgRecognizer} ms · preprocess/inference/postprocess avg ${avgRecognizerPreprocess}/${avgRecognizerInference}/${avgRecognizerPostprocess} ms · inference max ${recognizerInferenceMaxMs} ms · max ${recognizerMaxMs} ms · measured=${recognizerSamples}")
                appendLine("OCR pipeline overhead: avg ${avgPipelineOverhead} ms")
                appendLine("OCR cold model load max: ${coldLoadMaxMs} ms")
                appendLine("Translation engine: avg ${avgEngine} ms · max ${engineMaxMs} ms · measured frames=$engineSamples")
                appendLine("Translation: calls=$translationCalls · success=$translationSuccesses · failed=$translationFailures · blocks=$translationBlocks · cache hits/misses=$cacheHits/$cacheMisses · last engine=$lastEngine")
                appendLine("Overlay: expected=$overlayExpected · painted=$overlayPainted · failed=$overlayFailures")
                appendLine("Live status transitions: " + (liveStatuses.entries.joinToString { "${it.key}=${it.value}" }.ifBlank { "none" }))
                appendLine("Visible overlay outputs: $outputs")
                appendLine(E2ETrace.report("live"))
                appendLine(E2ETrace.report("tap"))
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

    fun recordLiveStatus(status: String) {
        synchronized(lock) {
            if (!active) return
            liveStatuses[status] = (liveStatuses[status] ?: 0L) + 1L
        }
    }

    fun recordFrameSize(width: Int, height: Int) {
        synchronized(lock) {
            if (!active) return
            frameWidth = width
            frameHeight = height
        }
    }

    fun recordPassOutcome(outcome: String) {
        synchronized(lock) {
            if (!active) return
            when (outcome) {
                "black" -> blackFrames++
                "empty" -> emptyOutputFrames++
                "failed" -> failedPasses++
                "cancelled" -> cancelledPasses++
            }
        }
    }

    fun recordTranslation(success: Boolean, engine: String?, blocks: Int, hits: Int, misses: Int) {
        synchronized(lock) {
            if (!active) return
            translationCalls++
            if (success) translationSuccesses++ else translationFailures++
            translationBlocks += blocks.coerceAtLeast(0)
            cacheHits += hits.coerceAtLeast(0)
            cacheMisses += misses.coerceAtLeast(0)
            if (!engine.isNullOrBlank()) lastEngine = engine.take(80)
        }
    }

    fun recordOverlay(painted: Int, expected: Int, failed: Boolean = false) {
        synchronized(lock) {
            if (!active) return
            overlayPainted += painted.coerceAtLeast(0)
            overlayExpected += expected.coerceAtLeast(0)
            if (failed) overlayFailures++
        }
    }

    fun recordOcrStats(stats: com.manhwalens.app.ocr.OcrRunStats?, lineCount: Int) {
        if (stats == null) {
            synchronized(lock) {
                if (active) ocrLines += lineCount.coerceAtLeast(0)
            }
            return
        }
        synchronized(lock) {
            if (!active) return
            detectorSamples++
            detectorTotalMs += stats.detectionTimeMs.coerceAtLeast(0L)
            detectorInferenceTotalMs += stats.detectionInferenceMs.coerceAtLeast(0L)
            detectorMaxMs = maxOf(detectorMaxMs, stats.detectionTimeMs)
            detectorInferenceMaxMs = maxOf(detectorInferenceMaxMs, stats.detectionInferenceMs)
            detectorPreprocessTotalMs += stats.detectionPreprocessMs.coerceAtLeast(0L)
            detectorPostprocessTotalMs += stats.detectionPostprocessMs.coerceAtLeast(0L)
            recognizerSamples++
            recognizerTotalMs += stats.recognitionTimeMs.coerceAtLeast(0L)
            recognizerInferenceTotalMs += stats.recognitionInferenceMs.coerceAtLeast(0L)
            recognizerMaxMs = maxOf(recognizerMaxMs, stats.recognitionTimeMs)
            recognizerInferenceMaxMs = maxOf(recognizerInferenceMaxMs, stats.recognitionInferenceMs)
            recognizerPreprocessTotalMs += stats.recognitionPreprocessMs.coerceAtLeast(0L)
            recognizerPostprocessTotalMs += stats.recognitionPostprocessMs.coerceAtLeast(0L)
            pipelineOverheadTotalMs += stats.pipelineOverheadMs.coerceAtLeast(0L)
            coldLoadMaxMs = maxOf(coldLoadMaxMs, stats.coldLoadTimeMs)
            ocrLines += lineCount.coerceAtLeast(0)
            ocrCandidates += stats.recognizedCandidateCount.coerceAtLeast(0)
            lowConfidenceDrops += stats.lowConfidenceDroppedCount.coerceAtLeast(0)
            lastOcrEngine = stats.engine.take(60)
            lastOcrConfig = "detector=${stats.detectionInputShape.joinToString("x")} cap=${stats.detectorMaxSideLimit} threads=${stats.engineThreads} candidates=${stats.recognizedCandidateCount} lowConf=${stats.lowConfidenceDroppedCount} recBatches=${stats.recognitionInputShapes.size}"
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

    fun recordOutput(
        queuedAtElapsedMs: Long,
        callbackStartedAtElapsedMs: Long,
        patchBuildStartedAtElapsedMs: Long,
        patchBuildFinishedAtElapsedMs: Long,
        overlayApplyStartedAtElapsedMs: Long
    ) {
        synchronized(lock) {
            if (!active) return
            outputs++
            if (firstOutputAtMs < 0L) {
                val outputAtElapsedMs = SystemClock.elapsedRealtime()
                firstOutputAtMs = (outputAtElapsedMs - startedElapsedMs).coerceAtLeast(0L)
                firstOverlayStageTimes = LiveOverlayStageTimes.measure(
                    patchBuildStartedAtElapsedMs = patchBuildStartedAtElapsedMs,
                    patchBuildFinishedAtElapsedMs = patchBuildFinishedAtElapsedMs,
                    queuedAtElapsedMs = queuedAtElapsedMs,
                    callbackStartedAtElapsedMs = callbackStartedAtElapsedMs,
                    overlayApplyStartedAtElapsedMs = overlayApplyStartedAtElapsedMs,
                    outputAppliedAtElapsedMs = outputAtElapsedMs
                )
                if (firstFramePassMs >= 0L && firstFrameStartedAtOffsetMs >= 0L) {
                    firstOutputPostFrameMs = (firstOutputAtMs - firstFrameStartedAtOffsetMs - firstFramePassMs).coerceAtLeast(0L)
                }
            }
        }
    }

    fun readReport(context: Context): String? =
        runCatching { File(context.applicationContext.filesDir, REPORT_FILE).takeIf { it.isFile }?.readText() }.getOrNull()

    private fun resetCounters() {
        E2ETrace.reset()
        frames = 0L
        passTotalMs = 0L
        passMaxMs = 0L
        firstFrameStartElapsedMs = -1L
        firstFrameStartedAtOffsetMs = -1L
        firstFramePassMs = -1L
        firstFrameOcrMs = -1L
        firstFrameEngineMs = -1L
        firstOutputPostFrameMs = -1L
        firstOverlayStageTimes = null
        detectorSamples = 0L
        detectorTotalMs = 0L
        detectorMaxMs = 0L
        detectorInferenceTotalMs = 0L
        detectorInferenceMaxMs = 0L
        detectorPreprocessTotalMs = 0L
        detectorPostprocessTotalMs = 0L
        recognizerSamples = 0L
        recognizerTotalMs = 0L
        recognizerMaxMs = 0L
        recognizerInferenceTotalMs = 0L
        recognizerInferenceMaxMs = 0L
        recognizerPreprocessTotalMs = 0L
        recognizerPostprocessTotalMs = 0L
        pipelineOverheadTotalMs = 0L
        coldLoadMaxMs = 0L
        ocrSamples = 0L
        ocrTotalMs = 0L
        ocrMaxMs = 0L
        engineSamples = 0L
        engineTotalMs = 0L
        engineMaxMs = 0L
        outputs = 0L
        firstOutputAtMs = -1L
        blackFrames = 0L
        emptyOutputFrames = 0L
        failedPasses = 0L
        cancelledPasses = 0L
        ocrLines = 0L
        ocrCandidates = 0L
        lowConfidenceDrops = 0L
        translationCalls = 0L
        translationSuccesses = 0L
        translationFailures = 0L
        translationBlocks = 0L
        cacheHits = 0L
        cacheMisses = 0L
        overlayExpected = 0L
        overlayPainted = 0L
        overlayFailures = 0L
        lastOcrEngine = "n/a"
        lastEngine = "n/a"
        lastOcrConfig = "n/a"
        frameWidth = 0
        frameHeight = 0
        liveStatuses.clear()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
