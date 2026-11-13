package com.manhwalens.app.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.manhwalens.app.ocr.geometry.Rect2D

/** Raw OCR output: lines ready for the bubble grouper. */
data class OcrResult(
    val lines: List<DetectedLine>,
    val elapsedMs: Long
)

/**
 * Blocking wrapper around ML Kit Latin Text Recognition (bundled model, no
 * network, works offline). Call from a background thread.
 */
class TextRecognizerClient {

    // Share one recognizer process-wide so the service pre-warm and the
    // capture path reuse the same loaded Latin model.
    private val client = SHARED_CLIENT

    /** Throws on failure. Internally logs duration + line count under tag MLCapture. */
    fun recognize(bitmap: Bitmap): OcrResult {
        val t0 = SystemClock.elapsedRealtime()
        // Downscale large frames BEFORE inference; boxes are scaled back up after.
        val (targetW, targetH) = BitmapScaler.computeTargetSize(bitmap.width, bitmap.height)
        val scaled = (targetW != bitmap.width || targetH != bitmap.height)
        val processing = if (scaled) Bitmap.createScaledBitmap(bitmap, targetW, targetH, true) else bitmap
        try {
            val image = InputImage.fromBitmap(processing, 0)
            val text = Tasks.await(client.process(image), OCR_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS)

            val inv = BitmapScaler.inverseScale(bitmap.width, bitmap.height, processing.width, processing.height)
            val lines = ArrayList<DetectedLine>()
            for (block in text.textBlocks) {
                for (line in block.lines) {
                    val bb: Rect = line.boundingBox ?: continue
                    val confidence = runCatching { line.confidence }.getOrDefault(0f)
                    lines += DetectedLine(
                        text = line.text,
                        box = Rect2D(
                            bb.left * inv, bb.top * inv, bb.right * inv, bb.bottom * inv
                        ),
                        confidence = confidence
                    )
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "OCR done: ${lines.size} lines in ${elapsed} ms" +
                if (scaled) " (frame ${bitmap.width}x${bitmap.height} downscaled to ${targetW}x${targetH})" else "")
            return OcrResult(lines, elapsed)
        } finally {
            if (scaled) processing.recycle()
        }
    }

    companion object {
        private const val TAG = "MLCapture"
        private const val OCR_TIMEOUT_SEC = 20L
        val SHARED_CLIENT: com.google.mlkit.vision.text.TextRecognizer =
            TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
}
