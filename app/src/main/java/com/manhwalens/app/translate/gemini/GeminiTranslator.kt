package com.manhwalens.app.translate.gemini

import android.util.Log
import com.manhwalens.app.translate.ArabicValidator
import com.manhwalens.app.translate.FailureReason
import com.manhwalens.app.translate.PageTranslation
import com.manhwalens.app.translate.Translator
import com.manhwalens.app.translate.TranslatedBlock
import com.manhwalens.app.translate.TranslationBlock
import com.manhwalens.app.util.PipelineTracer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Google Gemini engine — one batched generateContent request for the whole
 * page, numbered blocks carry the cross-bubble context the model needs.
 *
 * The API key is supplied by the caller (from TranslationPrefs, never from
 * source) and travels only in the HTTP header. Nothing key-related is logged.
 * The selected model is read from [modelProvider] on every call so a switch
 * takes effect immediately; on HTTP 404 the other known model is tried ONCE
 * and, if it works, persisted via [modelSaver].
 *
 * Call from a background thread.
 */
class GeminiTranslator(
    private val api: GeminiApi,
    private val modelProvider: () -> String = { GeminiWire.DEFAULT_MODEL },
    private val modelSaver: (String) -> Unit = {}
) : Translator {

    override val engineId = "gemini"

    override fun translatePage(blocks: List<TranslationBlock>): PageTranslation {
        val start = System.currentTimeMillis()
        if (blocks.isEmpty()) {
            return PageTranslation.Failure(FailureReason.EMPTY_INPUT, "No blocks to translate", engineId)
        }
        val translatable = blocks.filter { it.english.isNotBlank() }
        if (translatable.isEmpty()) {
            return PageTranslation.Success(
                blocks = blocks.map { TranslatedBlock(it.id, it.english, "") },
                engineId = engineId,
                elapsedMs = System.currentTimeMillis() - start,
                cacheHits = 0,
                cacheMisses = 0
            )
        }

        val requestBody = GeminiWire.buildRequestBody(translatable)
        val model = modelProvider()
        val tAttempt = System.currentTimeMillis()
        var result = try {
            api.generate(requestBody, model).also {
                PipelineTracer.span("gemini request", System.currentTimeMillis() - tAttempt, "model=$model http=${it.code}")
            }
        } catch (e: Throwable) {
            PipelineTracer.span("gemini request", System.currentTimeMillis() - tAttempt, "error=${e.javaClass.simpleName}")
            Log.e("ManhwaLens", "gemini request transport failure: ${e.javaClass.name}: ${e.message}")
            return PageTranslation.Failure(classifyTransport(e), briefMessage(e), engineId)
        }

        // HTTP 404 → the selected model id is wrong/retired. Retry ONCE with
        // the other known model; if it works, persist it and tell the caller.
        if (result.code == 400 && !result.body.contains("API key", ignoreCase = true)) {
            // The only optional field we send is thinkingConfig — if a model rejects the
            // request, try once more without it before reporting a failure.
            val tPlain = System.currentTimeMillis()
            val plain = try {
                api.generate(GeminiWire.buildRequestBody(translatable, null), model).also {
                    PipelineTracer.span("gemini 400 retry", System.currentTimeMillis() - tPlain, "no thinkingConfig http=${it.code}")
                }
            } catch (e: Throwable) {
                PipelineTracer.span("gemini 400 retry", System.currentTimeMillis() - tPlain, "error=${e.javaClass.simpleName}")
                return PageTranslation.Failure(classifyTransport(e), briefMessage(e), engineId)
            }
            result = plain
        }
        if (result.code == 404) {
            val retryModel = GeminiWire.otherModel(model)
            Log.i(TAG, "model '$model' → HTTP 404; retrying once with '$retryModel'")
            val tRetry = System.currentTimeMillis()
            val retry = try {
                api.generate(requestBody, retryModel).also {
                    PipelineTracer.span("gemini 404 retry", System.currentTimeMillis() - tRetry, "model=$retryModel http=${it.code}")
                }
            } catch (e: Throwable) {
                PipelineTracer.span("gemini 404 retry", System.currentTimeMillis() - tRetry, "error=${e.javaClass.simpleName}")
                Log.e("ManhwaLens", "gemini retry transport failure: ${e.javaClass.name}: ${e.message}")
                return PageTranslation.Failure(classifyTransport(e), briefMessage(e), engineId)
            }
            if (retry.code in 200..299) {
                runCatching { modelSaver(retryModel) }
                    .onFailure { Log.e("ManhwaLens", "failed to persist switched model", it) }
                Log.i(TAG, "model '$model' retired → now using '$retryModel'")
            }
            result = retry
        }

        when (result.code) {
            in 200..299 -> Unit
            else -> {
                PipelineTracer.span("gemini failed", System.currentTimeMillis() - start, "http=${result.code}")
                val snippet = bodySnippet(result.body)
                val reason = GeminiWire.reasonForHttp(result.code)
                val message = if (result.code == 404) {
                    "HTTP 404 — models '$model' and '${GeminiWire.otherModel(model)}' both unavailable"
                } else {
                    GeminiWire.humanForHttp(result.code, snippet, model)
                }
                return PageTranslation.Failure(reason, message, engineId)
            }
        }

        Log.i(TAG, "gemini response: ${result.body.length} bytes for ${translatable.size} blocks")
        val text = extractTextOrNull(result.body)
            ?: return PageTranslation.Failure(FailureReason.PARSE_ERROR, "No model text in response", engineId)

        val parsed = GeminiWire.parseNumberedLines(text)
            ?: return PageTranslation.Failure(FailureReason.PARSE_ERROR, "Model reply had no [id] numbered lines", engineId)

        val missing = translatable.map { it.id }.filter { parsed[it].isNullOrBlank() }
        if (missing.isNotEmpty()) {
            Log.e("ManhwaLens", "gemini response missing blocks: $missing")
            // v0.9.8: one skipped bubble no longer throws the whole page away. The reply is
            // rejected only when half of it or more is missing; otherwise the missing bubbles
            // come back blank (left untranslated / kept from the on-device pass).
            if (missing.size * 2 >= translatable.size) {
                return PageTranslation.Failure(
                    FailureReason.PARSE_ERROR,
                    "Model reply missed blocks: $missing",
                    engineId
                )
            }
            PipelineTracer.span("gemini partial", 0, "missing ${missing.size}/${translatable.size} block(s)")
        }

        // Never let an English echo or malformed string replace a readable ML Kit draft.
        // A proper name can remain Latin; ordinary dialogue must contain Arabic script.
        val rejected = translatable.mapNotNull { block ->
            val translated = parsed[block.id]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ArabicValidator.issue(block.english, translated)?.let { block.id to it }
        }
        if (rejected.isNotEmpty()) {
            val summary = rejected.take(4).joinToString { "#${it.first}:${it.second.name.lowercase()}" }
            PipelineTracer.span("gemini quality guard", 0, "rejected ${rejected.size}/${translatable.size} block(s)")
            return PageTranslation.Failure(
                FailureReason.PARSE_ERROR,
                "Translation quality check rejected ${rejected.size} block(s) ($summary)",
                engineId
            )
        }

        val arabicById = parsed // non-null here
        val ordered = blocks.map { b ->
            TranslatedBlock(b.id, b.english, arabicById[b.id] ?: "")
        }
        val elapsed = System.currentTimeMillis() - start
        Log.i(TAG, "gemini batch done: ${ordered.size} blocks in $elapsed ms")
        return PageTranslation.Success(
            blocks = ordered,
            engineId = engineId,
            elapsedMs = elapsed,
            cacheHits = 0,
            cacheMisses = 0
        )
    }

    private fun extractTextOrNull(body: String): String? = GeminiWire.extractText(body)

    private fun classifyTransport(e: Throwable): FailureReason = when (e) {
        is SocketTimeoutException,
        is InterruptedIOException -> FailureReason.TIMEOUT
        is UnknownHostException,
        is NoRouteToHostException,
        is ConnectException,
        is SocketException -> FailureReason.NO_INTERNET
        is SSLException -> FailureReason.NO_INTERNET
        is IOException -> FailureReason.NO_INTERNET
        else -> FailureReason.UNKNOWN
    }

    private fun briefMessage(e: Throwable): String =
        (e.message ?: e.javaClass.simpleName).take(120)

    /** Error body snippet with any accidental secrets stripped out. */
    private fun bodySnippet(body: String): String =
        body.replace(Regex("AIza[0-9A-Za-z_-]{10,}"), "[redacted]").take(160)

    companion object {
        private const val TAG = "MLCapture"
    }
}
