package com.manhwalens.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.manhwalens.app.data.SettingsPrefs
import com.manhwalens.app.data.TranslationPrefs
import com.manhwalens.app.render.BubbleRenderBox
import com.manhwalens.app.render.LargestBoxes
import com.manhwalens.app.ocr.geometry.Rect2D
import com.manhwalens.app.translate.OfflineTranslationPolicy
import com.manhwalens.app.translate.gemini.GeminiImageEditor
import com.manhwalens.app.translate.gemini.GeminiWire
import com.manhwalens.app.translate.gemini.HttpUrlConnectionGeminiApi
import com.manhwalens.app.util.PipelineTracer
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executor

/**
 * Owns the optional cloud image-cleanup feature.
 *
 * This component is deliberately separate from the foreground capture service:
 * it owns model discovery, crop encoding, Gemini requests and patch replacement.
 * Offline mode is checked both before scheduling and immediately before any
 * network work, so a queued task cannot cross the privacy boundary.
 */
internal class AiCleanupCoordinator(
    context: Context,
    private val executor: Executor,
    private val patchRect: (Int) -> Rect2D?,
    private val replaceBitmap: (Int, Bitmap) -> Unit
) {
    private val appContext = context.applicationContext

    @Volatile private var imageModelCache: Pair<String, String>? = null // apiKey -> modelId
    @Volatile private var imageModelCacheAtMs: Long = 0L

    fun schedule(frame: Bitmap, boxes: List<BubbleRenderBox>) {
        val settings = SettingsPrefs(appContext)
        if (!settings.aiCleanupEnabled) return

        val prefs = TranslationPrefs(appContext)
        if (!OfflineTranslationPolicy.canUseCloudServices(prefs.offlineMode)) {
            Log.i(TAG, "ai cleanup: skipped — offline mode is enabled")
            PipelineTracer.span("ai cleanup", 0, "skipped — offline mode")
            return
        }
        if (!prefs.hasGeminiKey) {
            Log.i(TAG, "ai cleanup: enabled but no Gemini key — kept local patches")
            return
        }
        if (prefs.geminiCircuit.isOpen()) {
            Log.i(TAG, "ai cleanup: Gemini circuit open (paused) — kept local patches")
            return
        }

        val candidates = LargestBoxes.select(boxes.filter { it.arabic.isNotBlank() }, 3)
        if (candidates.isEmpty()) return

        // Snapshot the crop rectangles before the next capture can change the page.
        val plans = candidates.map { box ->
            val left = (box.left - 10).coerceIn(0, frame.width - 1)
            val top = (box.top - 10).coerceIn(0, frame.height - 1)
            val right = (box.right + 10).coerceIn(left + 1, frame.width)
            val bottom = (box.bottom + 10).coerceIn(top + 1, frame.height)
            box to Rect(left, top, right, bottom)
        }

        executor.execute {
            // Re-read mutable preferences after queueing. A user may have enabled
            // Offline mode, disabled cleanup or removed the key while this task waited.
            val currentSettings = SettingsPrefs(appContext)
            val currentPrefs = TranslationPrefs(appContext)
            if (!currentSettings.aiCleanupEnabled) {
                PipelineTracer.span("ai cleanup", 0, "cancelled before network — feature disabled")
                return@execute
            }
            if (!OfflineTranslationPolicy.canUseCloudServices(currentPrefs.offlineMode)) {
                Log.i(TAG, "ai cleanup: queued request cancelled — offline mode is enabled")
                PipelineTracer.span("ai cleanup", 0, "cancelled before network — offline mode")
                return@execute
            }
            if (!currentPrefs.hasGeminiKey || currentPrefs.geminiCircuit.isOpen()) {
                Log.i(TAG, "ai cleanup: queued request cancelled — Gemini unavailable")
                PipelineTracer.span("ai cleanup", 0, "cancelled before network — Gemini unavailable")
                return@execute
            }

            val t0 = SystemClock.elapsedRealtime()
            val apiKey = currentPrefs.geminiKey
            val model = currentSettings.aiCleanupModelId.takeIf { it.isNotBlank() }
                ?: resolveImageCapableModelId(apiKey)
            if (model == null) {
                Log.w(TAG, "ai cleanup: no image-capable model for this key — kept local patches")
                PipelineTracer.span("ai cleanup", 0, "skipped — no image-capable model in account list (cloud)")
                return@execute
            }

            val api = HttpUrlConnectionGeminiApi(apiKey, AI_CLEANUP_READ_TIMEOUT_MS)
            var replaced = 0
            for ((box, rect) in plans) {
                // Offline mode may be switched on while this batch is processing.
                val latestPrefs = TranslationPrefs(appContext)
                if (!OfflineTranslationPolicy.canUseCloudServices(latestPrefs.offlineMode)) {
                    Log.i(TAG, "ai cleanup: remaining requests cancelled — offline mode is enabled")
                    break
                }
                val cropped = try {
                    Bitmap.createBitmap(frame, rect.left, rect.top, rect.width(), rect.height())
                } catch (e: Exception) {
                    Log.i(TAG, "ai cleanup: crop failed for #${box.id} — kept local patch (${e.javaClass.simpleName})")
                    null
                } ?: continue

                val jpeg = try {
                    ByteArrayOutputStream().use { out ->
                        cropped.compress(Bitmap.CompressFormat.JPEG, 92, out)
                        out.toByteArray()
                    }
                } catch (_: Exception) {
                    null
                } finally {
                    runCatching { cropped.recycle() }
                }
                if (jpeg == null) continue

                when (val result = GeminiImageEditor.editBubble(api, model, jpeg, box.arabic)) {
                    is com.manhwalens.app.translate.gemini.ImageEditResult.Success -> {
                        val decoded = BitmapFactory.decodeByteArray(result.imageBytes, 0, result.imageBytes.size)
                        val target = patchRect(box.id)
                        if (decoded != null && target != null) {
                            val width = (target.right - target.left).toInt().coerceAtLeast(1)
                            val height = (target.bottom - target.top).toInt().coerceAtLeast(1)
                            val scaled = if (decoded.width == width && decoded.height == height) decoded
                                else Bitmap.createScaledBitmap(decoded, width, height, true)
                            replaceBitmap(box.id, scaled)
                            if (scaled !== decoded) decoded.recycle()
                            replaced++
                        } else {
                            decoded?.let { runCatching { it.recycle() } }
                            Log.i(TAG, "ai cleanup: bubble #${box.id} — patch gone already, result discarded")
                        }
                    }
                    is com.manhwalens.app.translate.gemini.ImageEditResult.Failure -> {
                        Log.i(TAG, "ai cleanup: bubble #${box.id} kept local patch (${result.reason})")
                    }
                }
            }
            val elapsedMs = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "ai cleanup used (cloud) · replaced $replaced/${plans.size} bubble(s) · model=$model · $elapsedMs ms")
            PipelineTracer.span("ai cleanup", elapsedMs, "AI cleanup used (cloud) · replaced $replaced/${plans.size} · model=$model")
        }
    }

    /** Cached for 30 minutes per key; never hardcode a model name unsupported by the account. */
    private fun resolveImageCapableModelId(apiKey: String): String? {
        val now = SystemClock.elapsedRealtime()
        imageModelCache?.let { (cachedKey, modelId) ->
            if (cachedKey == apiKey && now - imageModelCacheAtMs < MODEL_CACHE_TTL_MS) return modelId
        }
        return try {
            val response = HttpUrlConnectionGeminiApi(apiKey).listModels()
            if (response.code !in 200..299) {
                Log.w(TAG, "ai cleanup: listModels http ${response.code}")
                null
            } else {
                val models = GeminiWire.imageCapableModels(GeminiWire.parseModelsBody(response.body))
                val picked = models.firstOrNull()?.let { GeminiWire.shortModelId(it.name) }
                if (picked != null) {
                    imageModelCache = apiKey to picked
                    imageModelCacheAtMs = now
                }
                picked
            }
        } catch (e: Exception) {
            Log.w(TAG, "ai cleanup: listModels failed — ${e.javaClass.name}: ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "MLCapture"
        const val AI_CLEANUP_READ_TIMEOUT_MS = 15_000
        const val MODEL_CACHE_TTL_MS = 30 * 60_000L
    }
}
