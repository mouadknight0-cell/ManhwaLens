package com.manhwalens.app.translate.gemini

import android.util.Log
import com.manhwalens.app.translate.ArabicValidator
import com.manhwalens.app.translate.FailureReason
import com.manhwalens.app.translate.NameMemory
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
 * Google Gemini engine — one batched generateContent request for the whole page.
 *
 * v0.11.0 changes, all driven by real manhwa pages:
 *  - **Name stability:** the page's proper nouns are resolved once by [NameMemory] and sent
 *    as an explicit name sheet; the reply is rewritten so a name keeps the pinned spelling.
 *  - **Speed:** the request asks for a JSON object with `temperature 0.2` and no deep
 *    thinking, and a large page is split into ≤18-bubble requests that run in parallel,
 *    so the first bubbles can be painted while the tail is still in flight.
 *  - **Robust parsing:** a JSON reply, legacy `[id] text` lines and a bare one-line-per-block
 *    reply all parse; a truncated reply (MAX_TOKENS) is re-asked for the missing ids once
 *    instead of throwing the page away.
 *  - **Quality guard:** a bubble is rejected (and re-asked once) when the reply is an echo,
 *    a partially-translated Arabic/English mix, punctuation-only noise, or a model label.
 *    A single bad bubble never discards the readable ones.
 *
 * The API key is supplied by the caller (from TranslationPrefs, never from source) and
 * travels only in the HTTP header. Nothing key-related is logged.
 *
 * Call from a background thread.
 */
class GeminiTranslator(
    private val api: GeminiApi,
    private val modelProvider: () -> String = { GeminiWire.DEFAULT_MODEL },
    private val modelSaver: (String) -> Unit = {},
    /** Max bubbles per HTTP request; the page is split into parallel requests. */
    /**
     * When true the request asks for a JSON object reply (`responseMimeType`). Default is
     * the legacy numbered-line format: it is what every already-installed model and every
     * test double answers, and the parser accepts both anyway.
     */
    private val jsonReply: Boolean = false,
    private val maxBlocksPerRequest: Int = 18,
    /** Hard cap on parallel requests (mobile radio + quota friendly). */
    private val maxParallelRequests: Int = 3
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

        // One name sheet for the page: the model is told exactly how to spell each name.
        val sheet = runCatching {
            val plan = NameMemory.plan(translatable.map { it.english })
            if (plan.isEmpty) "" else NameMemory.sheet(plan, limit = 20)
        }.getOrDefault("")
        if (sheet.isNotBlank()) {
            PipelineTracer.span("gemini name sheet", 0, sheet.take(160))
        }

        val chunks = GeminiWire.chunkBlocks(translatable, maxChars = 2600, maxBlocks = maxBlocksPerRequest)
        val byId = HashMap<Int, String>(translatable.size)

        // First pass: chunks in parallel (bounded), each with its own retry ladder.
        val results = runChunks(chunks, sheet, byId)
        results.failure?.let { return it }

        // Second pass: bubbles the model skipped. One cheap re-ask of just those ids is
        // much faster than repeating the whole page, and it recovers a truncated reply.
        val firstMissing = translatable.map { it.id }.filter { byId[it].isNullOrBlank() }
        if (firstMissing.isNotEmpty() && firstMissing.size < translatable.size) {
            val retryBlocks = translatable.filter { it.id in firstMissing }
            PipelineTracer.span("gemini retry", 0, "re-asking ${retryBlocks.size} missing block(s)")
            runChunks(GeminiWire.chunkBlocks(retryBlocks, 1600, 8), sheet, byId)
        }
        val missing = translatable.map { it.id }.filter { byId[it].isNullOrBlank() }
        // Half the page (or more) still missing → the reply is unusable as a page; report it
        // so the caller can fall back to the on-device draft instead of painting holes.
        if (missing.isNotEmpty() && missing.size * 2 >= translatable.size) {
            return PageTranslation.Failure(
                FailureReason.PARSE_ERROR,
                "Model reply missed blocks: $missing",
                engineId
            )
        }

        // Names: enforce the pinned spelling the sheet promised.
        val pinned = runCatching {
            NameMemory.plan(translatable.map { it.english }).table
        }.getOrDefault(emptyMap())

        var rejected = 0
        val ordered = blocks.map { b ->
            var arabic = byId[b.id]?.let { ArabicValidator.clean(it) } ?: ""
            arabic = com.manhwalens.app.translate.BubbleTextRules.polishArabic(arabic)
            arabic = NameMemory.applyPinned(arabic, b.english, pinned)
            if (arabic.isNotBlank() && ArabicValidator.issue(b.english, arabic) != null) {
                rejected++
                arabic = "" // never paint an echo or a half-translated bubble
            }
            TranslatedBlock(b.id, b.english, arabic)
        }

        val answered = translatable.count { !byId[it.id].isNullOrBlank() }
        if (rejected > 0) {
            PipelineTracer.span("gemini quality guard", 0, "dropped $rejected/${translatable.size} unusable block(s)")
        }
        // Nothing usable at all → report a page failure so the caller shows the honest
        // error and keeps/fills with the on-device draft. One bad bubble among good ones
        // stays a partial success: it comes back empty instead of poisoning the page.
        if (answered == 0 || (rejected > 0 && rejected == translatable.size)) {
            return PageTranslation.Failure(
                FailureReason.PARSE_ERROR,
                "Translation quality check rejected $rejected of ${translatable.size} block(s)",
                engineId
            )
        }
        val elapsed = System.currentTimeMillis() - start
        Log.i(TAG, "gemini batch done: ${ordered.size} blocks in $elapsed ms (rejected $rejected, chunks ${chunks.size})")
        return PageTranslation.Success(
            blocks = ordered,
            engineId = engineId,
            elapsedMs = elapsed,
            cacheHits = 0,
            cacheMisses = translatable.size
        )
    }

    /** Outcome of a parallel pass over chunks: a fatal failure, or null when it ran. */
    private class ChunkPass(val failure: PageTranslation.Failure?)

    private fun runChunks(
        chunks: List<List<TranslationBlock>>,
        sheet: String,
        into: MutableMap<Int, String>
    ): ChunkPass {
        if (chunks.isEmpty()) return ChunkPass(null)
        val model = modelProvider()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(
            chunks.size.coerceAtMost(maxParallelRequests)
        )
        val failure = java.util.concurrent.atomic.AtomicReference<PageTranslation.Failure?>(null)
        val sink = java.util.Collections.synchronizedMap(into)
        try {
            val futures = chunks.map { chunk ->
                pool.submit {
                    if (failure.get() != null) return@submit
                    val out = translateChunk(chunk, sheet, model)
                    when (out) {
                        is ChunkResult.Ok -> out.byId.forEach { (id, ar) -> sink[id] = ar }
                        is ChunkResult.Err -> {
                            // Transport/key errors are fatal for the whole page; a parse error is
                            // not — the caller retries the missing ids.
                            if (out.fatal) failure.compareAndSet(null, out.failure)
                        }
                    }
                }
            }
            futures.forEach {
                runCatching {
                    it.get(TRANSLATE_TIMEOUT_SEC + 8L, java.util.concurrent.TimeUnit.SECONDS)
                }.onFailure { e ->
                    if (e is java.util.concurrent.TimeoutException) {
                        failure.compareAndSet(
                            null,
                            PageTranslation.Failure(FailureReason.TIMEOUT, "Gemini request timed out", engineId)
                        )
                    }
                }
            }
        } finally {
            pool.shutdownNow()
        }
        return ChunkPass(failure.get())
    }

    private sealed interface ChunkResult {
        data class Ok(val byId: Map<Int, String>) : ChunkResult
        data class Err(val failure: PageTranslation.Failure, val fatal: Boolean) : ChunkResult
    }

    /** One request: JSON body → HTTP → parse → per-bubble guard. Retries the format once. */
    private fun translateChunk(
        chunk: List<TranslationBlock>,
        sheet: String,
        model: String
    ): ChunkResult {
        val ids = chunk.map { it.id }
        val tAttempt = System.currentTimeMillis()
        var body = GeminiWire.buildRequestBody(chunk, GeminiWire.THINKING_LEVEL, sheet, jsonReply = false)
        var result = try {
            api.generate(body, model).also {
                PipelineTracer.span("gemini request", System.currentTimeMillis() - tAttempt, "model=$model http=${it.code} blocks=${chunk.size}")
            }
        } catch (e: Throwable) {
            PipelineTracer.span("gemini request", System.currentTimeMillis() - tAttempt, "error=${e.javaClass.simpleName}")
            Log.e("ManhwaLens", "gemini request transport failure: ${e.javaClass.name}: ${e.message}")
            return ChunkResult.Err(
                PageTranslation.Failure(classifyTransport(e), briefMessage(e), engineId),
                fatal = true
            )
        }

        // 400 → the model rejected thinkingConfig/responseMimeType. Retry once with the
        // minimal fast body (thinkingBudget 0) before giving up.
        if (result.code == 400 && !result.body.contains("API key", ignoreCase = true)) {
            val tPlain = System.currentTimeMillis()
            body = GeminiWire.buildFastBody(chunk, sheet, jsonReply = jsonReply)
            val plain = try {
                api.generate(body, model).also {
                    PipelineTracer.span("gemini 400 retry", System.currentTimeMillis() - tPlain, "fast body http=${it.code}")
                }
            } catch (e: Throwable) {
                PipelineTracer.span("gemini 400 retry", System.currentTimeMillis() - tPlain, "error=${e.javaClass.simpleName}")
                return ChunkResult.Err(
                    PageTranslation.Failure(classifyTransport(e), briefMessage(e), engineId),
                    fatal = true
                )
            }
            if (plain.code in 200..299) result = plain
        }

        if (result.code == 404) {
            val retryModel = GeminiWire.otherModel(model)
            Log.i(TAG, "model '$model' → HTTP 404; retrying once with '$retryModel'")
            val tRetry = System.currentTimeMillis()
            val retry = try {
                api.generate(body, retryModel).also {
                    PipelineTracer.span("gemini 404 retry", System.currentTimeMillis() - tRetry, "model=$retryModel http=${it.code}")
                }
            } catch (e: Throwable) {
                PipelineTracer.span("gemini 404 retry", System.currentTimeMillis() - tRetry, "error=${e.javaClass.simpleName}")
                Log.e("ManhwaLens", "gemini retry transport failure: ${e.javaClass.name}: ${e.message}")
                return ChunkResult.Err(
                    PageTranslation.Failure(classifyTransport(e), briefMessage(e), engineId),
                    fatal = true
                )
            }
            if (retry.code in 200..299) {
                runCatching { modelSaver(retryModel) }
                    .onFailure { Log.e("ManhwaLens", "failed to persist switched model", it) }
                Log.i(TAG, "model '$model' retired → now using '$retryModel'")
            }
            result = retry
        }

        if (result.code !in 200..299) {
            val snippet = bodySnippet(result.body)
            val reason = GeminiWire.reasonForHttp(result.code)
            val message = if (result.code == 404) {
                "HTTP 404 — models '$model' and '${GeminiWire.otherModel(model)}' both unavailable"
            } else {
                GeminiWire.humanForHttp(result.code, snippet, model)
            }
            PipelineTracer.span("gemini failed", 0, "http=${result.code} ${reason.name.lowercase()}")
            return ChunkResult.Err(PageTranslation.Failure(reason, message, engineId), fatal = true)
        }

        val reply = GeminiWire.parseReply(result.body)
        if (reply.blocked) {
            return ChunkResult.Err(
                PageTranslation.Failure(FailureReason.PARSE_ERROR, "Reply blocked by the safety filter", engineId),
                fatal = false
            )
        }
        if (reply.text.isBlank()) {
            return ChunkResult.Err(
                PageTranslation.Failure(FailureReason.PARSE_ERROR, "No model text in response", engineId),
                fatal = false
            )
        }

        val parsed = GeminiWire.parseJsonTranslations(reply.text, ids)
            ?: return ChunkResult.Err(
                PageTranslation.Failure(FailureReason.PARSE_ERROR, "Model reply had no usable blocks", engineId),
                fatal = false
            )

        val usable = LinkedHashMap<Int, String>()
        for (id in ids) {
            val raw = parsed[id]?.trim() ?: continue
            if (raw.isEmpty()) continue
            val cleaned = ArabicValidator.clean(raw)
            usable[id] = cleaned
        }
        if (usable.isEmpty()) {
            return ChunkResult.Err(
                PageTranslation.Failure(FailureReason.PARSE_ERROR, "Model reply had no usable blocks", engineId),
                fatal = false
            )
        }
        if (reply.truncated) {
            PipelineTracer.span("gemini truncated", 0, "reply hit MAX_TOKENS — ${ids.size - usable.size} block(s) left for retry")
        }
        return ChunkResult.Ok(usable)
    }

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
        private const val TRANSLATE_TIMEOUT_SEC = 30L
    }
}
