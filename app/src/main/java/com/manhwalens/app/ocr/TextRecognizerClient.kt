package com.manhwalens.app.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.manhwalens.app.data.SettingsPrefs
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

/**
 * Selectable on-device OCR adapter. ML Kit remains the safe default; PaddleOCR
 * is an explicitly experimental, fully local alternative using PP-OCRv5 Arabic.
 * Call from a background thread.
 */
class TextRecognizerClient(context: Context) {
    private val appContext = context.applicationContext
    private val client = SHARED_CLIENT

    fun recognize(bitmap: Bitmap): OcrResult {
        return if (SettingsPrefs(appContext).ocrEngine == SettingsPrefs.OCR_ENGINE_PADDLE) {
            recognizePaddle(bitmap)
        } else {
            recognizeMlKit(bitmap)
        }
    }

    private fun recognizeMlKit(bitmap: Bitmap): OcrResult {
        val t0 = SystemClock.elapsedRealtime()
        val (targetW, targetH) = BitmapScaler.computeTargetSize(bitmap.width, bitmap.height)
        val scaled = targetW != bitmap.width || targetH != bitmap.height
        val processing = if (scaled) Bitmap.createScaledBitmap(bitmap, targetW, targetH, true) else bitmap
        try {
            val image = InputImage.fromBitmap(processing, 0)
            val text = Tasks.await(client.process(image), OCR_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)
            val inv = BitmapScaler.inverseScale(bitmap.width, bitmap.height, processing.width, processing.height)
            val lines = ArrayList<DetectedLine>()
            for (block in text.textBlocks) {
                for (line in block.lines) {
                    val bb: Rect = line.boundingBox ?: continue
                    lines += DetectedLine(
                        text = line.text,
                        box = Rect2D(bb.left * inv, bb.top * inv, bb.right * inv, bb.bottom * inv),
                        confidence = runCatching { line.confidence }.getOrDefault(0f)
                    )
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "OCR engine=ML Kit: ${lines.size} lines in ${elapsed} ms")
            return OcrResult(lines, elapsed)
        } finally {
            if (scaled) processing.recycle()
        }
    }

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
                "(det=${result.detectionTimeMs}ms rec=${result.recognitionTimeMs}ms load=${result.coldLoadTimeMs}ms)"
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
                        detLimitType = "max",
                        detMaxSideLimit = PADDLE_DET_MAX_SIDE
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
        private const val OCR_TIMEOUT_SEC = 20L
        private const val PADDLE_DET_LIMIT_SIDE = 1280
        private const val PADDLE_DET_MAX_SIDE = 1920
        private const val PADDLE_THREADS = 4
        private val PADDLE_LOCK = Any()

        @Volatile private var sharedPaddle: PaddleOCR? = null

        val SHARED_CLIENT: com.google.mlkit.vision.text.TextRecognizer =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
}
