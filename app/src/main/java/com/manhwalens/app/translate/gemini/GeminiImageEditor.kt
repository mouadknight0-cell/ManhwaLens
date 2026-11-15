package com.manhwalens.app.translate.gemini

import android.util.Base64
import com.manhwalens.app.translate.FailureReason

sealed class ImageEditResult {
    data class Success(val mimeType: String, val imageBytes: ByteArray) : ImageEditResult()
    data class Failure(val reason: String) : ImageEditResult()
}

/**
 * One-shot Gemini image-edit for ONE bubble (AI cleanup, Part D).
 * Sends ONLY the bubble crop (+10 px margin, encoded by the caller) — never the page.
 * ONE attempt, caller-supplied timeout. Silence on failure: the local patch stays.
 */
object GeminiImageEditor {

    fun editBubble(
        api: GeminiApi,
        modelId: String,
        cropJpegBytes: ByteArray,
        arabicText: String
    ): ImageEditResult {
        val instruction = GeminiWire.imageEditInstruction(arabicText)
        val b64 = Base64.encodeToString(cropJpegBytes, Base64.NO_WRAP)
        val body = GeminiWire.buildImageEditBody(b64, "image/jpeg", instruction)
        val result = try {
            api.generate(body, modelId)
        } catch (e: Exception) {
            val reason = when (e) {
                is java.net.SocketTimeoutException, is java.io.InterruptedIOException -> FailureReason.TIMEOUT.name
                is java.net.UnknownHostException, is java.net.NoRouteToHostException,
                is java.net.ConnectException -> FailureReason.NO_INTERNET.name
                else -> e.javaClass.simpleName
            }
            return ImageEditResult.Failure(reason)
        }
        if (result.code !in 200..299) return ImageEditResult.Failure("HTTP ${result.code}")
        val inline = GeminiWire.extractInlineImage(result.body)
            ?: return ImageEditResult.Failure("no image in response")
        val bytes = try {
            Base64.decode(inline.second, Base64.NO_WRAP)
        } catch (e: Exception) {
            return ImageEditResult.Failure("bad image encoding")
        }
        if (bytes.isEmpty()) return ImageEditResult.Failure("empty image")
        return ImageEditResult.Success(inline.first, bytes)
    }
}
