package com.manhwalens.app.translate.gemini

import com.manhwalens.app.translate.FailureReason
import com.manhwalens.app.translate.PageTranslation
import com.manhwalens.app.translate.TranslationBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** Scriptable fake of the HTTP seam — no network ever happens in unit tests. */
private class FakeGeminiApi(
    var result: GeminiHttpResult? = null,
    var throws: Throwable? = null,
    var listResult: GeminiHttpResult? = null,
    var resultsByModel: Map<String, GeminiHttpResult>? = null,
    /** Scripted per-call responses, consumed in order before [result] is used. */
    val queue: ArrayDeque<GeminiHttpResult> = ArrayDeque()
) : GeminiApi {
    val requestBodies = ArrayList<String>()
    val requestedModels = ArrayList<String>()
    var listCalls = 0

    override fun generate(requestBodyJson: String, model: String): GeminiHttpResult {
        requestBodies += requestBodyJson
        requestedModels += model
        throws?.let { throw it }
        if (queue.isNotEmpty()) return queue.removeFirst()
        return resultsByModel?.get(model) ?: result ?: GeminiHttpResult(500, "no scripted response")
    }

    override fun listModels(): GeminiHttpResult {
        listCalls++
        return listResult ?: GeminiHttpResult(200, "{\"models\":[]}")
    }
}

class GeminiTranslatorTest {

    private val blocks = listOf(
        TranslationBlock(1, "Hello!"),
        TranslationBlock(2, "Take my hand.")
    )

    private fun okBody(vararg lines: Pair<Int, String>): String {
        val text = lines.joinToString("\\n") { "[${it.first}] ${it.second}" }
        return """{"candidates":[{"content":{"parts":[{"text":"$text"}]}}]}"""
    }

    private fun translatorWith(api: FakeGeminiApi) = GeminiTranslator(api)

    @Test
    fun successReturnsOrderedArabicBlocksInOneRequest() {
        val api = FakeGeminiApi(result = GeminiHttpResult(200, okBody(1 to "مرحبا", 2 to "خذ يدي")))
        val result = translatorWith(api).translatePage(blocks) as PageTranslation.Success
        assertEquals(listOf(1, 2), result.blocks.map { it.id })
        assertEquals(listOf("مرحبا", "خذ يدي"), result.blocks.map { it.arabic })
        assertEquals(1, api.requestBodies.size) // one batched request for the page
    }

    @Test
    fun unknownHostMapsToNoInternet() {
        val api = FakeGeminiApi(throws = UnknownHostException("Unable to resolve host"))
        val result = translatorWith(api).translatePage(blocks) as PageTranslation.Failure
        assertEquals(FailureReason.NO_INTERNET, result.reason)
    }

    @Test
    fun socketTimeoutMapsToTimeout() {
        val api = FakeGeminiApi(throws = SocketTimeoutException("read timed out"))
        val result = translatorWith(api).translatePage(blocks) as PageTranslation.Failure
        assertEquals(FailureReason.TIMEOUT, result.reason)
    }

    @Test
    fun http401MapsToInvalidApiKey() {
        val api = FakeGeminiApi(result = GeminiHttpResult(401, """{"error":{"message":"API key not valid"}}"""))
        val result = translatorWith(api).translatePage(blocks) as PageTranslation.Failure
        assertEquals(FailureReason.INVALID_API_KEY, result.reason)
    }

    @Test
    fun http403MapsToInvalidApiKey() {
        val api = FakeGeminiApi(result = GeminiHttpResult(403, """{"error":{"message":"permission denied"}}"""))
        val result = translatorWith(api).translatePage(blocks) as PageTranslation.Failure
        assertEquals(FailureReason.INVALID_API_KEY, result.reason)
    }

    @Test
    fun http429MapsToRateLimited() {
        val api = FakeGeminiApi(result = GeminiHttpResult(429, """{"error":{"message":"quota"}}"""))
        val result = translatorWith(api).translatePage(blocks) as PageTranslation.Failure
        assertEquals(FailureReason.RATE_LIMITED, result.reason)
    }

    @Test
    fun http404RetriesWithAlternateModelAndPersistsIt() {
        val api = FakeGeminiApi(
            resultsByModel = mapOf(
                GeminiWire.DEFAULT_MODEL to GeminiHttpResult(404, "{\"error\":{\"message\":\"model not found\"}}"),
                GeminiWire.ALTERNATE_MODEL to GeminiHttpResult(200, okBody(1 to "مرحبا", 2 to "خذ يدي"))
            )
        )
        var savedModel: String? = null
        val translator = GeminiTranslator(
            api,
            modelProvider = { GeminiWire.DEFAULT_MODEL },
            modelSaver = { savedModel = it }
        )
        val result = translator.translatePage(blocks) as PageTranslation.Success
        assertEquals(listOf(1, 2), result.blocks.map { it.id })
        assertEquals(listOf(GeminiWire.DEFAULT_MODEL, GeminiWire.ALTERNATE_MODEL), api.requestedModels)
        assertEquals(GeminiWire.ALTERNATE_MODEL, savedModel)
    }

    @Test
    fun http404FromBothModelsMapsToModelNotFound() {
        val api = FakeGeminiApi(
            resultsByModel = mapOf(
                GeminiWire.DEFAULT_MODEL to GeminiHttpResult(404, "{\"error\":{\"message\":\"not found\"}}"),
                GeminiWire.ALTERNATE_MODEL to GeminiHttpResult(404, "{\"error\":{\"message\":\"not found\"}}")
            )
        )
        var savedModel: String? = null
        val translator = GeminiTranslator(
            api,
            modelProvider = { GeminiWire.DEFAULT_MODEL },
            modelSaver = { savedModel = it }
        )
        val result = translator.translatePage(blocks) as PageTranslation.Failure
        assertEquals(FailureReason.MODEL_NOT_FOUND, result.reason)
        assertTrue(result.message.contains(GeminiWire.DEFAULT_MODEL))
        assertTrue(result.message.contains(GeminiWire.ALTERNATE_MODEL))
        assertEquals(null, savedModel) // nothing to persist — both failed
    }

    @Test
    fun http400MapsToInvalidApiKey() {
        val api = FakeGeminiApi(result = GeminiHttpResult(400, "{\"error\":{\"message\":\"bad request\"}}"))
        val result = translatorWith(api).translatePage(blocks) as PageTranslation.Failure
        assertEquals(FailureReason.INVALID_API_KEY, result.reason)
        assertTrue(result.message.contains("400"))
    }

    @Test
    fun parseModelsBodyKeepsOnlyGenerateContent() {
        val body = "{\"models\":[" +
            "{\"name\":\"models/gemini-3.8-flash\",\"displayName\":\"Gemini 3.8 Flash\",\"supportedGenerationMethods\":[\"generateContent\"]}," +
            "{\"name\":\"models/embedding-1\",\"displayName\":\"Embedding\",\"supportedGenerationMethods\":[\"embedContent\"]}," +
            "{\"name\":\"models/gemini-3.5-flash-lite\",\"displayName\":\"Gemini 3.5 Flash-Lite\",\"supportedGenerationMethods\":[\"generateContent\",\"countTokens\"]}" +
            "]}"
        val models = GeminiWire.parseModelsBody(body)
        assertEquals(listOf("models/gemini-3.8-flash", "models/gemini-3.5-flash-lite"), models.map { it.name })
        assertTrue(models.all { it.supportsGenerateContent })
    }

    @Test
    fun http500MapsToUnknown() {
        val api = FakeGeminiApi(result = GeminiHttpResult(500, "internal"))
        val result = translatorWith(api).translatePage(blocks) as PageTranslation.Failure
        assertEquals(FailureReason.UNKNOWN, result.reason)
    }

    @Test
    fun malformedSuccessBodyMapsToParseError() {
        val api = FakeGeminiApi(result = GeminiHttpResult(200, "not json at all"))
        val result = translatorWith(api).translatePage(blocks) as PageTranslation.Failure
        assertEquals(FailureReason.PARSE_ERROR, result.reason)
    }

    @Test
    fun responseMissingABlockMapsToParseError() {
        val api = FakeGeminiApi(result = GeminiHttpResult(200, okBody(1 to "مرحبا"))) // block 2 missing
        val result = translatorWith(api).translatePage(blocks) as PageTranslation.Failure
        assertEquals(FailureReason.PARSE_ERROR, result.reason)
        assertTrue(result.message.contains("2"))
    }

    @Test
    fun oneMissingBlockOutOfThreeIsAcceptedAsPartial() {
        val three = blocks + TranslationBlock(3, "Third line")
        val api = FakeGeminiApi(result = GeminiHttpResult(200, okBody(1 to "مرحبا", 3 to "ثالث"))) // block 2 missing
        val result = translatorWith(api).translatePage(three) as PageTranslation.Success
        assertEquals("مرحبا", result.blocks.first { it.id == 1 }.arabic)
        assertEquals("", result.blocks.first { it.id == 2 }.arabic)
        assertEquals("ثالث", result.blocks.first { it.id == 3 }.arabic)
    }

    @Test
    fun englishEchoIsRejectedSoTheOfflineDraftCanRemainVisible() {
        val api = FakeGeminiApi(result = GeminiHttpResult(200, okBody(1 to "Hello!", 2 to "Take my hand.")))
        val result = translatorWith(api).translatePage(blocks) as PageTranslation.Failure
        assertEquals(FailureReason.PARSE_ERROR, result.reason)
        assertTrue(result.message.contains("quality check"))
    }

    @Test
    fun untranslatedStandaloneCharacterNameIsAllowed() {
        val name = listOf(TranslationBlock(9, "Eren"))
        val api = FakeGeminiApi(result = GeminiHttpResult(200, okBody(9 to "Eren")))
        val result = translatorWith(api).translatePage(name) as PageTranslation.Success
        assertEquals("Eren", result.blocks.single().arabic)
    }

    @Test
    fun requestAsksForLowThinking() {
        val api = FakeGeminiApi(result = GeminiHttpResult(200, okBody(1 to "مرحبا", 2 to "عالم")))
        translatorWith(api).translatePage(blocks)
        assertTrue(api.requestBodies.first().contains("\"thinkingLevel\":\"low\""))
    }

    @Test
    fun emptyInputMapsToEmptyInput() {
        val api = FakeGeminiApi()
        val result = translatorWith(api).translatePage(emptyList()) as PageTranslation.Failure
        assertEquals(FailureReason.EMPTY_INPUT, result.reason)
        assertEquals(0, api.requestBodies.size)
    }

    @Test
    fun blankBlocksAreNeverSentToTheApi() {
        val api = FakeGeminiApi(result = GeminiHttpResult(200, okBody(2 to "خذ يدي")))
        val withBlank = listOf(
            TranslationBlock(1, "   "),
            TranslationBlock(2, "Take my hand.")
        )
        val result = translatorWith(api).translatePage(withBlank) as PageTranslation.Success
        assertTrue(!api.requestBodies.single().contains("[1]"))
        assertTrue(api.requestBodies.single().contains("[2]"))
        assertEquals("", result.blocks.first { it.id == 1 }.arabic)
    }

    // ------------------------------------------------------------------
    // P0-A: the page name sheet must be in the FIRST request body — not
    // only in the HTTP-400 retry, and not only in the JSON format.
    // ------------------------------------------------------------------

    private val namedBlocks = listOf(
        TranslationBlock(1, "Lan Wangji drew his sword."),
        TranslationBlock(2, "Wei Wuxian said nothing.")
    )

    private val namedReply = GeminiHttpResult(
        200,
        okBody(1 to "لان وانغجي سحب سيفه.", 2 to "وي ووشيان لم يقل شيئا.")
    )

    /** The sheet the translator itself computes for these blocks (same code path). */
    private fun expectedSheet(): String {
        com.manhwalens.app.translate.Glossary.refresh("", true)
        return com.manhwalens.app.translate.NameMemory.sheet(
            com.manhwalens.app.translate.NameMemory.plan(namedBlocks.map { it.english }),
            limit = 20
        )
    }

    @Test
    fun primaryLegacyRequestCarriesThePageNameSheet() {
        val sheet = expectedSheet()
        assertTrue("sanity: this page must produce a name sheet", sheet.isNotBlank())
        val api = FakeGeminiApi(result = namedReply)
        // Default translator = legacy numbered-lines body: the production primary path.
        translatorWith(api).translatePage(namedBlocks)
        // Exactly one request: the primary one, not a 400 retry and not a re-ask.
        assertEquals(1, api.requestBodies.size)
        val first = api.requestBodies.first()
        sheet.split("; ").forEach { line ->
            assertTrue("first request is missing sheet line '$line'", first.contains(line))
        }
        // The legacy path must stay legacy (no JSON reply mode) while carrying the names.
        assertTrue(first.contains("\"thinkingLevel\":\"low\""))
        assertTrue(!first.contains("responseMimeType"))
    }

    @Test
    fun jsonRetryBodyCarriesThePageNameSheetToo() {
        val sheet = expectedSheet()
        assertTrue("sanity: this page must produce a name sheet", sheet.isNotBlank())
        // 400 → the translator retries once with the JSON "fast" body (responseMimeType).
        // That retry used to be the ONLY place the sheet survived; it must keep carrying it.
        val api = FakeGeminiApi(
            queue = ArrayDeque(
                listOf(
                    GeminiHttpResult(400, """{"error":{"message":"bad request"}}"""),
                    namedReply
                )
            )
        )
        GeminiTranslator(api, jsonReply = true).translatePage(namedBlocks)
        assertEquals(2, api.requestBodies.size)
        val retry = api.requestBodies[1]
        sheet.split("; ").forEach { line ->
            assertTrue("JSON retry body is missing sheet line '$line'", retry.contains(line))
        }
        assertTrue(retry.contains("\"responseMimeType\":\"application/json\""))
        // And the primary request that preceded it carried the sheet as well.
        sheet.split("; ").forEach { line ->
            assertTrue("primary body is missing sheet line '$line'", api.requestBodies[0].contains(line))
        }
    }

    @Test
    fun pageWithoutNamesSendsNoMalformedPrompt() {
        com.manhwalens.app.translate.Glossary.refresh("", true)
        val plain = listOf(TranslationBlock(1, "Hello!"), TranslationBlock(2, "Take my hand."))
        val api = FakeGeminiApi(result = GeminiHttpResult(200, okBody(1 to "مرحبا", 2 to "خذ يدي")))
        translatorWith(api).translatePage(plain)
        val first = api.requestBodies.first()
        assertTrue(first, first.contains("[1] Hello!"))
        assertTrue(first, first.contains("[2] Take my hand."))
        assertTrue("no null must reach the prompt", !first.contains("null"))
        assertTrue("the prompt text must never be empty", !first.contains("\"text\":\"\""))
        assertTrue(first.split(GeminiWire.GLOSSARY_HEADER).size - 1 <= 1)
    }
}
