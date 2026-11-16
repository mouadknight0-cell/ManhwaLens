package com.manhwalens.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.manhwalens.app.ocr.geometry.Rect2D
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/** Raw OCR output: lines ready for the bubble grouper. */
data class OcrResult(
    val lines: List<DetectedLine>,
    val elapsedMs: Long,
    val engineStats: OcrRunStats? = null
)

/** Fixed on-device OCR adapter. PaddleOCR PP-OCRv5 Arabic/English runs locally. Call from a background thread. */
class TextRecognizerClient(context: Context) {
    private val appContext = context.applicationContext
    fun recognize(bitmap: Bitmap): OcrResult = recognizePaddle(bitmap)

    private fun recognizePaddle(bitmap: Bitmap): OcrResult = synchronized(PADDLE_LOCK) {
        // The same lock protects model teardown, so ONNX sessions cannot be closed mid-inference.
        val t0 = SystemClock.elapsedRealtime()
        val paddle = paddleClient()
        val result = runBlocking(Dispatchers.IO) { paddle.recognize(bitmap) }
        val lines = result.results.mapNotNull { item ->
            val points = item.box.points
            if (points.isEmpty()) return@mapNotNull null
            val left = points.minOf { it.x }
            val top = points.minOf { it.y }
            val right = points.maxOf { it.x }
            val bottom = points.maxOf { it.y }
            if (right <= left || bottom <= top || item.text.isBlank()) return@mapNotNull null
            DetectedLine(
                text = item.text.trim(),
                box = Rect2D(left, top, right, bottom),
                confidence = item.confidence
            )
        }
        val elapsed = SystemClock.elapsedRealtime() - t0
        Log.i(
            TAG,
            "OCR engine=PaddleOCR: ${lines.size} lines in ${elapsed} ms " +
                "(det=${result.detectionTimeMs}ms rec=${result.recognitionTimeMs}ms load=${result.coldLoadTimeMs}ms " +
                "candidates=${result.recognizedCandidateCount} rejectedLowConfidence=${result.lowConfidenceDroppedCount} " +
                "threshold=${PADDLE_MIN_REC_CONFIDENCE})"
        )
        OcrResult(
            lines = lines,
            elapsedMs = elapsed,
            engineStats = OcrRunStats(
                engine = "PaddleOCR",
                detectionTimeMs = result.detectionTimeMs,
                recognitionTimeMs = result.recognitionTimeMs,
                totalTimeMs = result.totalTimeMs,
                detectionPreprocessMs = result.detPreprocessMs,
                detectionInferenceMs = result.detInferenceMs,
                detectionPostprocessMs = result.detPostprocessMs,
                recognitionPreprocessMs = result.recPreprocessMs,
                recognitionInferenceMs = result.recInferenceMs,
                recognitionPostprocessMs = result.recPostprocessMs,
                pipelineOverheadMs = result.pipelineOverheadMs,
                coldLoadTimeMs = result.coldLoadTimeMs,
                detectionInputShape = result.detInputShape,
                recognitionInputShapes = result.recInputShapes,
                recognizedCandidateCount = result.recognizedCandidateCount,
                lowConfidenceDroppedCount = result.lowConfidenceDroppedCount,
                confidenceThreshold = PADDLE_MIN_REC_CONFIDENCE,
                detectionThreshold = PADDLE_DET_THRESHOLD,
                detectionBoxThreshold = PADDLE_DET_BOX_THRESHOLD,
                detectionUnclipRatio = PADDLE_DET_UNCLIP_RATIO,
                detectorLimitSideLen = PADDLE_DET_LIMIT_SIDE,
                detectorMaxSideLimit = PADDLE_DET_MAX_SIDE,
                detectorLimitType = PADDLE_DET_LIMIT_TYPE,
                engineThreads = PADDLE_THREADS
            )
        )
    }

    private fun paddleClient(): PaddleOCR {
        sharedPaddle?.let { return it }
        synchronized(PADDLE_LOCK) {
            sharedPaddle?.let { return it }
            check(OpenCVUtils.init(appContext)) { "PaddleOCR OpenCV initialization failed" }
            val created = runBlocking(Dispatchers.IO) {
                PaddleOCR.create(
                    context = appContext,
                    config = PaddleOCRConfig(
                        detLimitSideLen = PADDLE_DET_LIMIT_SIDE,
                        // Cap detector input; recognition later crops from the original source image.
                        detLimitType = PADDLE_DET_LIMIT_TYPE,
                        detMaxSideLimit = PADDLE_DET_MAX_SIDE,
                        detThresh = PADDLE_DET_THRESHOLD,
                        detBoxThresh = PADDLE_DET_BOX_THRESHOLD,
                        detUnclipRatio = PADDLE_DET_UNCLIP_RATIO,
                        recScoreThresh = PADDLE_MIN_REC_CONFIDENCE,
                        recBatchSize = PADDLE_REC_BATCH_SIZE
                    ),
                    engineConfig = EngineConfig(numThreads = PADDLE_THREADS)
                )
            }
            sharedPaddle = created
            return created
        }
    }

    companion object {
        private const val TAG = "MLCapture"
        private const val PADDLE_DET_LIMIT_SIDE = 1280
        private const val PADDLE_DET_LIMIT_TYPE = "min"
        private const val PADDLE_DET_MAX_SIDE = 1280
        private const val PADDLE_DET_THRESHOLD = 0.3f
        private const val PADDLE_DET_BOX_THRESHOLD = 0.6f
        private const val PADDLE_DET_UNCLIP_RATIO = 1.5f
        private const val PADDLE_THREADS = 4
        // Trial: batch up to eight recognition crops to reduce sequential ONNX calls on dense pages.
        // A real-device benchmark must confirm latency and recall before claiming a speedup.
        private const val PADDLE_REC_BATCH_SIZE = 8
        // PaddleOCR's documented default drop_score is 0.5; filter uncertain lines before grouping.
        private const val PADDLE_MIN_REC_CONFIDENCE = 0.5f
        private val PADDLE_LOCK = Any()

        @Volatile private var sharedPaddle: PaddleOCR? = null

        /**
         * Release process-wide ONNX sessions when Android reports memory pressure or the
         * foreground service is stopped. Caller must run this off the main thread.
         * The same lock is held by recognizePaddle(), so release waits for active inference.
         */
        fun releaseShared() {
            synchronized(PADDLE_LOCK) {
                val current = sharedPaddle ?: return
                sharedPaddle = null
                runBlocking(Dispatchers.IO) { current.release() }
            }
        }

    }
}
