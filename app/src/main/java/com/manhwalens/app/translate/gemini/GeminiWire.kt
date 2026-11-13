package com.manhwalens.app.translate.gemini

import com.manhwalens.app.translate.Glossary
import com.manhwalens.app.translate.TranslationBlock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure wire-format helpers for the Gemini `generateContent` REST endpoint.
 * No I/O here — everything is unit-testable on the JVM.
 *
 * The API key is passed exclusively in the `x-goog-api-key` HTTP header by the
 * caller (never in this body, never in logs).
 */
object GeminiWire {

    const val DEFAULT_MODEL = "gemini-3.8-flash"
    const val ALTERNATE_MODEL = "gemini-3.5-flash-lite"

    /** Reasoning effort requested for translation calls. */
    const val THINKING_LEVEL = "low"
    const val ENDPOINT_BASE = "https://generativelanguage.googleapis.com/v1beta/models"

    /** Map an HTTP status to a FailureReason (only called for non-2xx). */
    fun reasonForHttp(code: Int) = when (code) {
        400, 401, 403 -> com.manhwalens.app.translate.FailureReason.INVALID_API_KEY
        404 -> com.manhwalens.app.translate.FailureReason.MODEL_NOT_FOUND
        429 -> com.manhwalens.app.translate.FailureReason.RATE_LIMITED
        else -> com.manhwalens.app.translate.FailureReason.UNKNOWN
    }

    /**
     * Short human message for an HTTP failure, always with the HTTP code.
     * [snippet] must be pre-redacted (see translator's bodySnippet). Never contains the key.
     */
    fun humanForHttp(code: Int, snippet: String, model: String? = null): String = when (code) {
        400 -> "HTTP 400 — request rejected (check the API key and model id)" +
            (if (snippet.isBlank()) "" else " · " + snippet.replace(Regex("\\s+"), " ").take(90))
        401, 403 -> "HTTP $code — invalid or unauthorized API key (check settings)"
        404 -> "HTTP 404 — model '${model ?: "unknown"}' not found (see available models)"
        429 -> "HTTP 429 — rate limit or quota exceeded; try again later"
        else -> "HTTP $code — ${snippet.ifBlank { "server error" }}"
    }

    /** All models the user can pick in settings (default first). */
    val MODEL_OPTIONS = listOf(DEFAULT_MODEL, ALTERNATE_MODEL)

    /** The other known-good model — used for the single retry on HTTP 404. */
    fun otherModel(model: String): String =
        if (model == DEFAULT_MODEL) ALTERNATE_MODEL else DEFAULT_MODEL

    fun endpoint(model: String): String = "$ENDPOINT_BASE/$model:generateContent"

    fun modelsEndpoint(): String = ENDPOINT_BASE

    /** One model entry from the models.list response. */
    data class GeminiModelInfo(
        val name: String,               // e.g. "models/gemini-3.8-flash"
        val displayName: String,
        val supportsGenerateContent: Boolean
    )

    /** Strip the "models/" resource prefix for a bare model id. */
    fun shortModelId(name: String): String = name.removePrefix("models/")

    /**
     * Filter the LIVE models list down to image-capable models for the AI-cleanup
     * feature — the user picks one of these in Settings. Never a hardcoded id.
     * Matches on the id/display name containing "image" or "imagen" (e.g.
     * gemini-*-image-generation, imagen-*). Text-only models are excluded.
     */
    fun imageCapableModels(models: List<GeminiModelInfo>): List<GeminiModelInfo> =
        models.filter { m ->
            val n = m.name.lowercase()
            val d = m.displayName.lowercase()
            "image" in n || "image" in d || "imagen" in n || "imagen" in d
        }

    /**
     * Parse the models.list response. Returns only entries that support
     * generateContent (what this app needs). Never throws — empty list on junk.
     */
    fun parseModelsBody(body: String): List<GeminiModelInfo> = try {
        val root = JSONObject(body)
        val arr = root.optJSONArray("models") ?: return emptyList()
        val out = ArrayList<GeminiModelInfo>(arr.length())
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val name = m.optString("name")
            if (name.isBlank()) continue
            val methods = m.optJSONArray("supportedGenerationMethods")
            var supports = false
            if (methods != null) {
                for (j in 0 until methods.length()) {
                    if (methods.optString(j) == "generateContent") { supports = true; break }
                }
            }
            out += GeminiModelInfo(name, m.optString("displayName", name), supports)
        }
        out.filter { it.supportsGenerateContent }
    } catch (e: Exception) {
        emptyList()
    }

    private const val SYSTEM_INSTRUCTION =
        "You are a professional Arabic translator for English-language comics and webtoons. " +
            "Translate each numbered block into natural, fluent Modern Standard Arabic " +
            "suitable for speech bubbles (clean, readable, not dialect). " +
            "Strict rules: " +
            "(1) Reply with exactly one line per block, format: [<id>] <arabic translation>; " +
            "(2) Keep the number of blocks identical and keep the same ids; " +
            "(3) Never explain, never merge or split blocks; " +
            "(4) Transliterate sound effects naturally into Arabic (for example: KRAK becomes كراك); " +
            "(5) Keep unknown proper nouns and names unchanged; " +
            "(6) Each bubble's translation must be ONE continuous sentence — even if the Arabic " +
            "wraps across multiple visual lines, do not split one sentence into fragments; " +
            "(7) Consecutive blocks belong to the same page — use them to resolve context and pronouns; " +
            "(8) Names and titles already written in Arabic in the text come from the reader's glossary: " +
            "keep them exactly as written, never re-translate or re-transliterate them; " +
            "(9) Spell every name the same way on every block of the page."

    fun userPrompt(blocks: List<TranslationBlock>): String =
        blocks.joinToString("\n") { "[${it.id}] ${it.english.trim()}" }

    /**
     * v0.9.14: the source side of a real request. Each bubble is already one sentence and the
     * known Chinese names are already Arabic ([Glossary.prepare]); a glossary hint list follows
     * so the model keeps that spelling page after page.
     */
    fun promptFor(blocks: List<TranslationBlock>): String {
        val prepared = blocks.joinToString("\n") { "[${it.id}] ${Glossary.prepare(it.english)}" }
        val hints = runCatching { Glossary.promptHints() }.getOrDefault("")
        if (hints.isBlank()) return prepared
        return prepared + "\n\n" + GLOSSARY_HEADER + " " + hints
    }

    const val GLOSSARY_HEADER =
        "Glossary (already applied above; keep these spellings):"

    /** Full request body. The key is NOT part of this JSON. */
    fun buildRequestBody(blocks: List<TranslationBlock>): String = buildRequestBody(blocks, THINKING_LEVEL)

    /**
     * [thinkingLevel] null = leave the model's default. Translation needs no deep reasoning:
     * the Flash default ("medium") routinely took longer than the HTTP timeout, so the app
     * asks for "low" (accepted by every model in [MODEL_OPTIONS]).
     */
    fun buildRequestBody(blocks: List<TranslationBlock>, thinkingLevel: String?): String {
        val root = JSONObject()
        root.put(
            "systemInstruction",
            JSONObject().put(
                "parts",
                JSONArray().put(JSONObject().put("text", SYSTEM_INSTRUCTION))
            )
        )
        root.put(
            "contents",
            JSONObject().put(
                "parts",
                JSONArray().put(JSONObject().put("text", promptFor(blocks)))
            )
        )
        val generationConfig = JSONObject()
            .put("temperature", 0.3)
            .put("maxOutputTokens", 8192)
        if (!thinkingLevel.isNullOrBlank()) {
            generationConfig.put("thinkingConfig", JSONObject().put("thinkingLevel", thinkingLevel))
        }
        root.put("generationConfig", generationConfig)
        return root.toString()
    }

    /** Extract the model text out of a generateContent response. Null when unusable. */
    fun extractText(responseBody: String): String? = try {
        val root = JSONObject(responseBody)
        val candidates = root.optJSONArray("candidates") ?: return null
        if (candidates.length() == 0) return null
        val parts = candidates.getJSONObject(0)
            .optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return null
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val t = parts.getJSONObject(i).optString("text", "")
            if (t.isNotEmpty()) sb.append(t).append('\n')
        }
        sb.toString().trim().ifEmpty { null }
    } catch (e: Exception) {
        null
    }

    // ---------- image-edit (AI cleanup) wire helpers ----------

    const val IMAGE_EDIT_INSTRUCTION =
        "Replace the English text in this speech bubble with the following Arabic text: %s. " +
            "Keep the bubble shape, outline, background, art style and size exactly the same. " +
            "Arabic must be right-to-left with connected letters. Return only the edited image."

    /** Instruction sent with the bubble crop. [arabicText] is the already-translated text. */
    fun imageEditInstruction(arabicText: String): String =
        IMAGE_EDIT_INSTRUCTION.format(arabicText.trim())

    /**
     * Build the generateContent body for an image-edit call.
     * The key is NOT part of this JSON (it travels in the x-goog-api-key header).
     */
    fun buildImageEditBody(imageBase64: String, mimeType: String, instruction: String): String {
        val root = JSONObject()
        val parts = JSONArray()
            .put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", mimeType).put("data", imageBase64)
                )
            )
            .put(JSONObject().put("text", instruction))
        root.put("contents", JSONObject().put("parts", parts))
        root.put(
            "generationConfig",
            JSONObject()
                .put("responseModalities", JSONArray().put("TEXT").put("IMAGE"))
                .put("temperature", 0.2)
                .put("maxOutputTokens", 8192)
        )
        return root.toString()
    }

    /**
     * Extract the FIRST inline image from a generateContent response.
     * Returns (mimeType, base64Data) or null when the model refused / returned text only.
     */
    fun extractInlineImage(responseBody: String): Pair<String, String>? = try {
        val root = JSONObject(responseBody)
        if (root.has("promptFeedback") && root.optJSONObject("promptFeedback")?.has("blockReason") == true) {
            null
        } else {
            val candidates = root.optJSONArray("candidates")
            var out: Pair<String, String>? = null
            if (candidates != null) {
                loop@ for (i in 0 until candidates.length()) {
                    val parts = candidates.optJSONObject(i)
                        ?.optJSONObject("content")
                        ?.optJSONArray("parts") ?: continue
                    for (j in 0 until parts.length()) {
                        val part = parts.optJSONObject(j) ?: continue
                        val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")
                        val data = inline?.optString("data", "") ?: ""
                        if (data.isNotEmpty()) {
                            out = (inline.optString("mimeType", "image/png")) to data
                            break@loop
                        }
                    }
                }
            }
            out
        }
    } catch (e: Exception) {
        null
    }

    private val numberedLine = Regex("(?m)^\\s*\\[(\\d+)\\]\\s*(.+?)\\s*$")

    /**
     * Parse "[12] نص عربي" lines into an id→translation map.
     * Returns null when NOTHING parses; callers may check id coverage themselves.
     */
    fun parseNumberedLines(text: String): Map<Int, String>? {
        val out = LinkedHashMap<Int, String>()
        for (match in numberedLine.findAll(text)) {
            val id = match.groupValues[1].toIntOrNull() ?: continue
            val body = match.groupValues[2].trim()
            if (body.isNotEmpty()) out[id] = body
        }
        return out.ifEmpty { null }
    }
}
