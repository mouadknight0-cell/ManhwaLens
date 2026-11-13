package com.manhwalens.app.translate.libre

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

data class LibreTranslateHttpResult(val code: Int, val body: String)

/** HTTP seam kept small so request/response handling can be unit-tested without network access. */
fun interface LibreTranslateApi {
    fun translate(endpoint: String, requestBodyJson: String): LibreTranslateHttpResult
}

/** HTTPS-only implementation of the self-hosted LibreTranslate REST contract. */
class HttpUrlConnectionLibreTranslateApi(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS
) : LibreTranslateApi {

    override fun translate(endpoint: String, requestBodyJson: String): LibreTranslateHttpResult {
        require(LibreTranslateWire.translateEndpoint(endpoint) == endpoint) {
            "A valid HTTPS LibreTranslate endpoint is required"
        }
        val startedAt = System.currentTimeMillis()
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            connection.outputStream.use { output ->
                output.write(requestBodyJson.toByteArray(StandardCharsets.UTF_8))
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use(::readBoundedUtf8) ?: ""
            // Never log the body (it can contain user text or server details) or API key.
            Log.i(TAG, "LibreTranslate HTTP $code · ${System.currentTimeMillis() - startedAt} ms")
            return LibreTranslateHttpResult(code, body)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBoundedUtf8(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (out.size() + count > MAX_RESPONSE_BYTES) {
                throw IOException("LibreTranslate response exceeded the size limit")
            }
            out.write(buffer, 0, count)
        }
        return String(out.toByteArray(), StandardCharsets.UTF_8)
    }

    companion object {
        private const val TAG = "ManhwaLens"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_RESPONSE_BYTES = 256 * 1024
    }
}
