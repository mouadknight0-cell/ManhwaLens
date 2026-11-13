package com.manhwalens.app.translate.libre

import com.manhwalens.app.translate.FailureReason
import com.manhwalens.app.translate.PageTranslation
import com.manhwalens.app.translate.TranslationBlock
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

private class FakeLibreTranslateApi(
    var result: LibreTranslateHttpResult = LibreTranslateHttpResult(
        200,
        """{"translatedText":["مرحبًا","خذ يدي"]}"""
    ),
    var throws: Exception? = null
) : LibreTranslateApi {
    val endpoints = mutableListOf<String>()
    val bodies = mutableListOf<String>()

    override fun translate(endpoint: String, requestBodyJson: String): LibreTranslateHttpResult {
        endpoints += endpoint
        bodies += requestBodyJson
        throws?.let { throw it }
        return result
    }
}

class LibreTranslateTranslatorTest {

    @Test
    fun translatesWholePageInOneRequestAndPreservesIdsAndOrder() {
        val api = FakeLibreTranslateApi()
        val translator = LibreTranslateTranslator(
            api = api,
            endpointProvider = { "https://translate.example" },
            apiKeyProvider = { "key-for-test" }
        )
        val input = listOf(
            TranslationBlock(8, "Hello!"),
            TranslationBlock(9, "   "),
            TranslationBlock(2, "Take my hand.")
        )

        val result = translator.translatePage(input) as PageTranslation.Success

        assertEquals("libre_translate", result.engineId)
        assertEquals(listOf(8, 9, 2), result.blocks.map { it.id })
        assertEquals(listOf("مرحبًا", "", "خذ يدي"), result.blocks.map { it.arabic })
        assertEquals(1, api.bodies.size)
        assertEquals("https://translate.example/translate", api.endpoints.single())
        val request = JSONObject(api.bodies.single())
        assertEquals(2, request.getJSONArray("q").length())
        assertEquals("key-for-test", request.getString("api_key"))
        assertEquals(2, result.cacheMisses)
    }

    @Test
    fun invalidEndpointFailsBeforeMakingNetworkCall() {
        val api = FakeLibreTranslateApi()
        val translator = LibreTranslateTranslator(api, { "http://insecure.example" })
        val result = translator.translatePage(listOf(TranslationBlock(1, "Hello"))) as PageTranslation.Failure
        assertEquals(FailureReason.UNKNOWN, result.reason)
        assertTrue(result.message.contains("HTTPS"))
        assertTrue(api.bodies.isEmpty())
    }

    @Test
    fun malformedOrPartialPageResponseIsRejected() {
        val api = FakeLibreTranslateApi(
            result = LibreTranslateHttpResult(200, """{"translatedText":["مرحبًا"]}""")
        )
        val translator = LibreTranslateTranslator(api, { "https://translate.example" })
        val result = translator.translatePage(
            listOf(TranslationBlock(1, "Hello!"), TranslationBlock(2, "Take my hand."))
        ) as PageTranslation.Failure
        assertEquals(FailureReason.PARSE_ERROR, result.reason)
    }

    @Test
    fun qualityGuardRejectsAnEnglishEchoWithoutCachingItAsSuccess() {
        val api = FakeLibreTranslateApi(
            result = LibreTranslateHttpResult(200, """{"translatedText":["Hello!"]}""")
        )
        val translator = LibreTranslateTranslator(api, { "https://translate.example" })
        val result = translator.translatePage(listOf(TranslationBlock(1, "Hello!"))) as PageTranslation.Failure
        assertEquals(FailureReason.PARSE_ERROR, result.reason)
        assertTrue(result.message.contains("quality check"))
    }

    @Test
    fun httpAuthAndRateLimitFailuresAreClassified() {
        val auth = LibreTranslateTranslator(
            FakeLibreTranslateApi(result = LibreTranslateHttpResult(401, """{"error":"bad key"}""")),
            { "https://translate.example" }
        ).translatePage(listOf(TranslationBlock(1, "Hello"))) as PageTranslation.Failure
        assertEquals(FailureReason.INVALID_API_KEY, auth.reason)

        val rate = LibreTranslateTranslator(
            FakeLibreTranslateApi(result = LibreTranslateHttpResult(429, """{"error":"slow down"}""")),
            { "https://translate.example" }
        ).translatePage(listOf(TranslationBlock(1, "Hello"))) as PageTranslation.Failure
        assertEquals(FailureReason.RATE_LIMITED, rate.reason)
    }

    @Test
    fun networkTimeoutIsReportedAndBlankBlocksDoNotCallServer() {
        val api = FakeLibreTranslateApi(throws = SocketTimeoutException("read timed out"))
        val translator = LibreTranslateTranslator(api, { "https://translate.example" })
        val timeout = translator.translatePage(listOf(TranslationBlock(1, "Hello"))) as PageTranslation.Failure
        assertEquals(FailureReason.TIMEOUT, timeout.reason)

        val blankApi = FakeLibreTranslateApi()
        val blank = LibreTranslateTranslator(blankApi, { "https://translate.example" }).translatePage(
            listOf(TranslationBlock(1, "  "))
        ) as PageTranslation.Success
        assertEquals("", blank.blocks.single().arabic)
        assertNull(blankApi.bodies.singleOrNull())
    }
}
