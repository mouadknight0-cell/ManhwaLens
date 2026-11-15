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
    val elapsedMs: Long
)

/** Fixed on-device OCR adapter. PaddleOCR PP-OCRv5 Arabic/English runs locally. Call from a background thread. */
class TextRecognizerClient(context: Context) {
    private val appContext = context.applicationContext
    fun recognize(bitmap: Bitmap): OcrResult = recognizePaddle(bitmap)

    private fun recognizePaddle(bitmap: Bitmap): OcrResult {
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
        return OcrResult(lines, elapsed)
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
                        // Keep narrow phone text large; the 1920px cap bounds CPU and memory use.
                        detLimitType = "min",
                        detMaxSideLimit = PADDLE_DET_MAX_SIDE,
                        recScoreThresh = PADDLE_MIN_REC_CONFIDENCE
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
        private const val PADDLE_DET_MAX_SIDE = 1920
        private const val PADDLE_THREADS = 4
        // PaddleOCR's documented default drop_score is 0.5; filter uncertain lines before grouping.
        private const val PADDLE_MIN_REC_CONFIDENCE = 0.5f
        private val PADDLE_LOCK = Any()

        @Volatile private var sharedPaddle: PaddleOCR? = null

    }
}
