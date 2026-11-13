package com.manhwalens.app.translate.gemini

import android.util.Log
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class GeminiHttpResult(val code: Int, val body: String)

/** Thin seam around the Gemini HTTP call so engines are unit-testable without network. */
interface GeminiApi {
    /**
     * Returns the HTTP response. Expected to THROW on transport failures
     * (UnknownHostException, SocketTimeoutException, …) so the translator can
     * map them to FailureReason.
     */
    fun generate(requestBodyJson: String, model: String): GeminiHttpResult

    /** GET the models list (used by Settings → "Check available models"). */
    fun listModels(): GeminiHttpResult
}

/**
 * Real implementation on HttpURLConnection. The key travels ONLY in the
 * x-goog-api-key header — never in the URL, never in the body, never in logs.
 * Network exceptions propagate for the translator to classify.
 */
class HttpUrlConnectionGeminiApi(
    private val apiKey: String,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS
) : GeminiApi {

    override fun generate(requestBodyJson: String, model: String): GeminiHttpResult {
        val t0 = System.currentTimeMillis()
        val conn = URL(GeminiWire.endpoint(model)).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(requestBodyJson)
            }
            val code = conn.responseCode
            // Never log the key or the request; log only status + size + time.
            Log.i(TAG, "gemini http $code for model=$model in ${System.currentTimeMillis() - t0} ms")
            val body = readBody(conn, code)
            return GeminiHttpResult(code, body)
        } finally {
            conn.disconnect()
        }
    }

    override fun listModels(): GeminiHttpResult {
        val t0 = System.currentTimeMillis()
        val conn = URL(GeminiWire.modelsEndpoint()).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("x-goog-api-key", apiKey)
            val code = conn.responseCode
            Log.i(TAG, "gemini listModels http $code in ${System.currentTimeMillis() - t0} ms")
            val body = readBody(conn, code)
            return GeminiHttpResult(code, body)
        } finally {
            conn.disconnect()
        }
    }

    private fun readBody(conn: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream ?: conn.inputStream
        return stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
    }

    companion object {
        private const val TAG = "MLCapture"
        /** v0.9.8: 3 s / 6 s were too tight for mobile networks and for thinking models. */
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
    }
}
