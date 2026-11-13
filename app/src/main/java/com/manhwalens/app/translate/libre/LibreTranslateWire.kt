package com.manhwalens.app.translate.libre

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest

/** Pure helpers for the documented LibreTranslate `/translate` JSON API. */
object LibreTranslateWire {

    /**
     * Accept only HTTPS endpoints; API keys and captured dialogue must not traverse cleartext.
     * Users may enter either the server base URL or its full `/translate` endpoint.
     */
    fun translateEndpoint(baseUrl: String): String? {
        val trimmed = baseUrl.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_ENDPOINT_LENGTH || trimmed.any(Char::isWhitespace)) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        if (uri.port != -1 && uri.port !in 1..65535) return null

        val withoutTrailingSlash = trimmed.trimEnd('/')
        val path = uri.path.orEmpty().trimEnd('/')
        val endpoint = if (path.endsWith("/translate", ignoreCase = true)) {
            withoutTrailingSlash
        } else {
            "$withoutTrailingSlash/translate"
        }
        return runCatching { URI(endpoint).toASCIIString() }.getOrNull()
    }

    /** Build one page-wide request. LibreTranslate accepts `q` as a string array. */
    fun buildRequestBody(texts: List<String>, apiKey: String = ""): String {
        val root = JSONObject()
        root.put("q", JSONArray(texts))
        root.put("source", SOURCE_LANGUAGE)
        root.put("target", TARGET_LANGUAGE)
        root.put("format", "text")
        apiKey.trim().takeIf { it.isNotEmpty() }?.let { root.put("api_key", it) }
        return root.toString()
    }

    /** Return exactly one translated string per submitted text, or null for a malformed reply. */
    fun parseTranslatedTexts(responseBody: String, expectedCount: Int): List<String>? {
        if (expectedCount < 1) return null
        return try {
            val value = JSONObject(responseBody).opt("translatedText")
            when (value) {
                is JSONArray -> {
                    if (value.length() != expectedCount) return null
                    val outputs = ArrayList<String>(expectedCount)
                    for (i in 0 until value.length()) {
                        val item = value.opt(i)
                        if (item !is String) return null
                        outputs.add(item)
                    }
                    outputs
                }
                is String -> if (expectedCount == 1) listOf(value) else null
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /** A short, non-secret error description suitable for a user-facing failure message. */
    fun errorMessage(responseBody: String, apiKey: String = ""): String {
        val raw = try {
            val json = JSONObject(responseBody)
            json.optString("error").ifBlank { json.optString("message") }
        } catch (_: Exception) {
            ""
        }
        if (raw.isBlank()) return ""
        var safe = raw.replace(Regex("[\\r\\n\\t]+"), " ").trim()
        if (apiKey.isNotBlank()) safe = safe.replace(apiKey, "[redacted]")
        safe = safe.replace(Regex("(?i)(api[_-]?key\\s*[=:]\\s*)[^&\\s,]+"), "${'$'}1[redacted]")
        return safe.take(MAX_ERROR_LENGTH)
    }

    /** Changes to a self-hosted endpoint get a separate cache namespace; secrets are excluded. */
    fun endpointCacheTag(baseUrl: String): String? {
        val endpoint = translateEndpoint(baseUrl) ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(endpoint.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return digest.take(12)
    }

    private const val SOURCE_LANGUAGE = "en"
    private const val TARGET_LANGUAGE = "ar"
    private const val MAX_ENDPOINT_LENGTH = 1024
    private const val MAX_ERROR_LENGTH = 160
}
