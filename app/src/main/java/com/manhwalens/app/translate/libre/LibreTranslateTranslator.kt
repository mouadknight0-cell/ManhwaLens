package com.manhwalens.app.translate.libre

import com.manhwalens.app.translate.ArabicValidator
import com.manhwalens.app.translate.FailureReason
import com.manhwalens.app.translate.Glossary
import com.manhwalens.app.translate.PageTranslation
import com.manhwalens.app.translate.SceneNames
import com.manhwalens.app.translate.TranslatedBlock
import com.manhwalens.app.translate.TranslationBlock
import com.manhwalens.app.translate.Translator
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
 * Optional, self-hosted English → Arabic engine using LibreTranslate's page-array API.
 * The endpoint is user-configured; the app does not ship or operate a translation server.
 * OCR text is glossary-prepared and sent as one batch, never as a screenshot.
 */
class LibreTranslateTranslator(
    private val api: LibreTranslateApi,
    private val endpointProvider: () -> String,
    private val apiKeyProvider: () -> String = { "" },
    /** P1: authoritative page/series name spellings, applied before LibreTranslate sees the text. */
    private val nameTableProvider: ((List<String>) -> Map<String, String>)? = null
) : Translator {

    override val engineId = ENGINE_ID

    override fun translatePage(blocks: List<TranslationBlock>): PageTranslation {
        val startedAt = System.currentTimeMillis()
        if (blocks.isEmpty()) {
            return PageTranslation.Failure(FailureReason.EMPTY_INPUT, "No blocks to translate", engineId)
        }

        val translatable = blocks.filter { it.english.isNotBlank() }
        if (translatable.isEmpty()) {
            return PageTranslation.Success(
                blocks = blocks.map { TranslatedBlock(it.id, it.english, "") },
                engineId = engineId,
                elapsedMs = System.currentTimeMillis() - startedAt,
                cacheHits = 0,
                cacheMisses = 0
            )
        }

        val endpoint = LibreTranslateWire.translateEndpoint(runCatching(endpointProvider).getOrDefault(""))
            ?: return PageTranslation.Failure(
                FailureReason.UNKNOWN,
                "Set a valid HTTPS LibreTranslate server URL in translation settings",
                engineId
            )
        val apiKey = runCatching(apiKeyProvider).getOrDefault("").trim()
        // LibreTranslate cannot follow prompt instructions. Pin the same resolved page table
        // used by ML Kit into the source text before sending the request.
        val nameTable = runCatching { nameTableProvider?.invoke(translatable.map { it.english }) }
            .getOrNull().orEmpty()
        val prepared = translatable.map { block ->
            SceneNames.apply(Glossary.prepare(block.english), nameTable)
        }
        val requestBody = LibreTranslateWire.buildRequestBody(prepared, apiKey)
        val response = try {
            api.translate(endpoint, requestBody)
        } catch (e: Exception) {
            return PageTranslation.Failure(classifyTransport(e), briefMessage(e), engineId)
        }

        if (response.code !in 200..299) {
            val detail = LibreTranslateWire.errorMessage(response.body, apiKey)
            val suffix = if (detail.isBlank()) "" else " · $detail"
            val reason = when (response.code) {
                401, 403 -> FailureReason.INVALID_API_KEY
                429 -> FailureReason.RATE_LIMITED
                else -> FailureReason.UNKNOWN
            }
            return PageTranslation.Failure(
                reason,
                "LibreTranslate HTTP ${response.code}$suffix",
                engineId
            )
        }

        val outputs = LibreTranslateWire.parseTranslatedTexts(response.body, translatable.size)
            ?: return PageTranslation.Failure(
                FailureReason.PARSE_ERROR,
                "LibreTranslate returned an incomplete or malformed page response",
                engineId
            )

        val translatedById = LinkedHashMap<Int, String>()
        val rejected = translatable.indices.mapNotNull { index ->
            val block = translatable[index]
            val issue = ArabicValidator.issue(block.english, outputs[index])
            if (issue == null) {
                translatedById[block.id] = outputs[index]
                null
            } else {
                block.id to issue
            }
        }
        // A bad response for one bubble must not erase valid translations from the same page.
        // Rejected bubbles stay blank; never substitute their English source.
        if (translatedById.isEmpty()) {
            val summary = rejected.take(4).joinToString { "#${it.first}:${it.second.name.lowercase()}" }
            PipelineTracer.span("libre quality guard", 0, "rejected all ${translatable.size} block(s)")
            return PageTranslation.Failure(
                FailureReason.PARSE_ERROR,
                "Translation quality check rejected all ${translatable.size} block(s) ($summary)",
                engineId
            )
        }
        if (rejected.isNotEmpty()) {
            val summary = rejected.take(4).joinToString { "#${it.first}:${it.second.name.lowercase()}" }
            PipelineTracer.span("libre partial quality guard", 0, "accepted ${translatedById.size}/${translatable.size}; rejected ${rejected.size} block(s) ($summary)")
        }
        val elapsedMs = System.currentTimeMillis() - startedAt
        PipelineTracer.span("libre translate batch", elapsedMs, "${translatable.size} block(s), 1 request")
        return PageTranslation.Success(
            blocks = blocks.map { block ->
                TranslatedBlock(block.id, block.english, translatedById[block.id] ?: "")
            },
            engineId = engineId,
            elapsedMs = elapsedMs,
            cacheHits = 0,
            cacheMisses = translatable.size
        )
    }

    private fun classifyTransport(error: Throwable): FailureReason = when (error) {
        is SocketTimeoutException, is InterruptedIOException -> FailureReason.TIMEOUT
        is UnknownHostException,
        is NoRouteToHostException,
        is ConnectException,
        is SocketException,
        is SSLException,
        is IOException -> FailureReason.NO_INTERNET
        else -> FailureReason.UNKNOWN
    }

    private fun briefMessage(error: Throwable): String =
        (error.message ?: error.javaClass.simpleName).take(120)

    companion object {
        const val ENGINE_ID = "libre_translate"
    }
}
