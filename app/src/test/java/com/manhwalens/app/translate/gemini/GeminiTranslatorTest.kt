package com.manhwalens.app.translate.gemini

import com.manhwalens.app.translate.FailureReason
import com.manhwalens.app.translate.Glossary
import com.manhwalens.app.translate.NameMemory
import com.manhwalens.app.translate.NameSheet
import com.manhwalens.app.translate.PageTranslation
import com.manhwalens.app.translate.TranslationBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun finishedBody(reason: String, vararg lines: Pair<Int, String>): String {
        val text = lines.joinToString("\\n") { "[${it.first}] ${it.second}" }
        return """{"candidates":[{"finishReason":"$reason","content":{"parts":[{"text":"$text"}]}}]}"""
    }

    @Test
    fun maxTokensRetriesOnlyMissingIdsInSmallerChunksAndKeepsCompletedBubbles() {
        val api = FakeGeminiApi(
            queue = ArrayDeque(
                listOf(
                    GeminiHttpResult(200, finishedBody("MAX_TOKENS", 1 to "مرحبا")),
                    GeminiHttpResult(200, okBody(2 to "خذ يدي"))
                )
            )
        )

        val result = translatorWith(api).translatePage(blocks) as PageTranslation.Success

        assertEquals(listOf("مرحبا", "خذ يدي"), result.blocks.map { it.arabic })
        assertEquals(2, api.requestBodies.size)
        assertTrue(api.requestBodies[0].contains("[1] Hello!"))
        assertTrue(api.requestBodies[0].contains("[2] Take my hand."))
        assertTrue(api.requestBodies[1].contains("[2] Take my hand."))
        assertFalse("completed id must not be requested again", api.requestBodies[1].contains("[1] Hello!"))
    }

    @Test
    fun maxTokensRetrySplitsMissingBubblesAtTheSmallerFourBlockLimit() {
        val many = (1..6).map { TranslationBlock(it, "Bubble $it needs translation") }
        val api = FakeGeminiApi(
            queue = ArrayDeque(
                listOf(
                    GeminiHttpResult(200, finishedBody("MAX_TOKENS", 1 to "فقاعة واحد")),
                    GeminiHttpResult(200, okBody(2 to "فقاعة اثنان", 3 to "فقاعة ثلاثة", 4 to "فقاعة أربعة", 5 to "فقاعة خمسة")),
                    GeminiHttpResult(200, okBody(6 to "فقاعة ستة"))
                )
            )
        )

        val result = translatorWith(api).translatePage(many) as PageTranslation.Success

        assertEquals(6, result.blocks.size)
        assertEquals(3, api.requestBodies.size)
        assertTrue(api.requestBodies[1].contains("[2] Bubble 2 needs translation"))
        assertTrue(api.requestBodies[1].contains("[5] Bubble 5 needs translation"))
        assertFalse(api.requestBodies[1].contains("[6] Bubble 6 needs translation"))
        assertTrue(api.requestBodies[2].contains("[6] Bubble 6 needs translation"))
        assertFalse(api.requestBodies[1].contains("[1] Bubble 1 needs translation"))
    }

    @Test
    fun maxTokensWithNoUsableTextStillRetriesTheEntireMissingChunk() {
        val api = FakeGeminiApi(
            queue = ArrayDeque(
                listOf(
                    GeminiHttpResult(200, """{"candidates":[{"finishReason":"MAX_TOKENS","content":{"parts":[]}}]}"""),
                    GeminiHttpResult(200, okBody(1 to "مرحبا", 2 to "خذ يدي"))
                )
            )
        )

        val result = translatorWith(api).translatePage(blocks) as PageTranslation.Success

        assertEquals(listOf("مرحبا", "خذ يدي"), result.blocks.map { it.arabic })
        assertEquals(2, api.requestBodies.size)
        assertTrue(api.requestBodies[1].contains("[1] Hello!"))
        assertTrue(api.requestBodies[1].contains("[2] Take my hand."))
    }

    @Test
    fun safetyFinishWithoutTextIsNotMisclassifiedAsTokenLimit() {
        val api = FakeGeminiApi(
            result = GeminiHttpResult(
                200,
                """{"candidates":[{"finishReason":"SAFETY","content":{"parts":[]}}]}"""
            )
        )

        val result = translatorWith(api).translatePage(blocks) as PageTranslation.Failure

        assertEquals(FailureReason.PARSE_ERROR, result.reason)
        assertEquals(1, api.requestBodies.size)
    }

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
        // 400 → the translator retries once. That retry used to be the ONLY place the sheet
        // survived; it must keep carrying it. P2-17 changed the retry FORMAT (it now drops the
        // JSON reply mode) but not this guarantee.
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
            assertTrue("retry body is missing sheet line '$line'", retry.contains(line))
        }
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

    // ------------------------------------------------------------------
    // P2-03: [GeminiTranslator.jsonReply] must reach the PRIMARY request,
    // not only the HTTP-400 retry, and the shipped default must not change.
    // ------------------------------------------------------------------

    /** A generateContent envelope whose model text is [text] (quotes/newlines escaped). */
    private fun jsonEnvelope(text: String): String {
        val escaped = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"$escaped\"}]}}]}"
    }

    private val jsonReplyBody = jsonEnvelope("""{"t":{"1":"مرحبا","2":"خذ يدي"}}""")

    @Test
    fun jsonReplyTrueSendsTheJsonPrimaryRequest() {
        val api = FakeGeminiApi(result = GeminiHttpResult(200, jsonReplyBody))
        val result = GeminiTranslator(api, jsonReply = true)
            .translatePage(blocks) as PageTranslation.Success
        // Exactly one request: the primary one. Before P2-03 this body was the legacy one.
        assertEquals(1, api.requestBodies.size)
        val primary = api.requestBodies.first()
        assertTrue(primary, primary.contains("\"responseMimeType\":\"application/json\""))
        // The JSON body keeps its own tuning: temperature 0.2, no thinking budget.
        assertTrue(primary, primary.contains("\"temperature\":0.2"))
        assertTrue(primary, primary.contains("\"thinkingBudget\":0"))
        // And the JSON reply shape is parsed through the same path.
        assertEquals(listOf("مرحبا", "خذ يدي"), result.blocks.map { it.arabic })
    }

    @Test
    fun jsonReplyFalseKeepsTheLegacyPrimaryRequest() {
        val api = FakeGeminiApi(result = GeminiHttpResult(200, okBody(1 to "مرحبا", 2 to "خذ يدي")))
        GeminiTranslator(api, jsonReply = false).translatePage(blocks)
        val primary = api.requestBodies.first()
        assertTrue(primary, primary.contains("\"thinkingLevel\":\"low\""))
        assertTrue(primary, primary.contains("\"temperature\":0.3"))
        assertTrue(
            "the legacy body must not request a JSON reply",
            !primary.contains("responseMimeType")
        )
    }

    @Test
    fun defaultConstructorKeepsTheLegacyPrimaryRequest() {
        // P2-03 must not flip the shipped default: TranslatorFactory passes no flag.
        val api = FakeGeminiApi(result = GeminiHttpResult(200, okBody(1 to "مرحبا", 2 to "خذ يدي")))
        GeminiTranslator(api, modelProvider = { GeminiWire.DEFAULT_MODEL }).translatePage(blocks)
        assertEquals(1, api.requestBodies.size)
        assertTrue(!api.requestBodies.first().contains("responseMimeType"))
        assertTrue(api.requestBodies.first().contains("\"thinkingLevel\":\"low\""))
    }

    @Test
    fun http400RetryStillRunsWithJsonReplyTrue() {
        // P2-17: the primary request keeps the caller's JSON mode; the retry must NOT. While the
        // retry reused the same flag it was byte-identical to the request that had just been
        // rejected with 400, so it could never recover from a 400 caused by `responseMimeType`.
        val api = FakeGeminiApi(
            queue = ArrayDeque(
                listOf(
                    GeminiHttpResult(400, "{\"error\":{\"message\":\"bad request\"}}"),
                    GeminiHttpResult(200, jsonReplyBody)
                )
            )
        )
        val result = GeminiTranslator(api, jsonReply = true)
            .translatePage(blocks) as PageTranslation.Success
        assertEquals(2, api.requestBodies.size)
        val primary = api.requestBodies[0]
        val retry = api.requestBodies[1]
        // First body: JSON, exactly as the caller asked.
        assertTrue(primary, primary.contains("\"responseMimeType\":\"application/json\""))
        assertTrue(primary, primary.contains("\"temperature\":0.2"))
        // Second body: the fast body without the JSON reply mode — thinkingBudget 0, no
        // responseMimeType, temperature 0.2, and none of the deep-thinking config.
        assertTrue("the retry must not ask for a JSON reply again", !retry.contains("responseMimeType"))
        assertTrue(retry, retry.contains("\"thinkingBudget\":0"))
        assertTrue(retry, retry.contains("\"temperature\":0.2"))
        assertTrue(retry, !retry.contains("thinkingLevel"))
        // The two bodies genuinely differ, so the retry is a new attempt rather than a replay.
        assertTrue("the 400 retry repeated the failed request verbatim", primary != retry)
        // And the parser still accepts the retry's own reply shape.
        assertEquals(listOf("مرحبا", "خذ يدي"), result.blocks.map { it.arabic })
    }

    @Test
    fun http400RetryStillRunsWithJsonReplyFalse() {
        val api = FakeGeminiApi(
            queue = ArrayDeque(
                listOf(
                    GeminiHttpResult(400, "{\"error\":{\"message\":\"bad request\"}}"),
                    GeminiHttpResult(200, okBody(1 to "مرحبا", 2 to "خذ يدي"))
                )
            )
        )
        val result = GeminiTranslator(api, jsonReply = false)
            .translatePage(blocks) as PageTranslation.Success
        assertEquals(2, api.requestBodies.size)
        assertTrue(!api.requestBodies[0].contains("responseMimeType"))
        // The retry keeps its fast, non-JSON body: thinkingBudget 0 and no responseMimeType.
        assertTrue(!api.requestBodies[1].contains("responseMimeType"))
        assertTrue(api.requestBodies[1].contains("\"thinkingBudget\":0"))
        assertEquals(listOf("مرحبا", "خذ يدي"), result.blocks.map { it.arabic })
    }

    @Test
    fun onlyHttp400IsRetriedWithTheLegacyBody() {
        // P2-17 must not turn the 400 recovery into a general retry policy.
        for (code in listOf(401, 403, 429, 500, 503)) {
            val api = FakeGeminiApi(result = GeminiHttpResult(code, "{\"error\":{\"message\":\"nope\"}}"))
            val result = GeminiTranslator(api, jsonReply = true).translatePage(blocks)
            assertTrue("HTTP $code must stay a failure", result is PageTranslation.Failure)
            assertEquals("HTTP $code must not be retried", 1, api.requestBodies.size)
        }
    }


    // ------------------------------------------------------------------
    // P2-05 (decision C′): ONE resolved table supplies both halves of the
    // name pipeline — the sheet text in the prompt AND the map the repair
    // applies. These tests fail on the pre-C′ wiring, where the engine
    // rebuilt its own table from NameMemory.plan (chains D-A/D-B, §5.14.2).
    // ------------------------------------------------------------------

    private val hanBlocks = listOf(
        TranslationBlock(1, "Han Ji-woo drew his sword."),
        TranslationBlock(2, "Wei Wuxian said nothing.")
    )

    @Test
    fun theSheetAndTheRepairComeFromTheSameTable() {
        Glossary.refresh("", true)
        val resolved = NameSheet("Han Ji-woo = هان جيو", mapOf("Han Ji-woo" to "هان جيو"))
        val api = FakeGeminiApi(
            result = GeminiHttpResult(200, okBody(1 to "Han Ji-woo سحب سيفه.", 2 to "وي ووشيان لم يقل شيئا."))
        )

        val result = GeminiTranslator(api, nameSheetProvider = { resolved })
            .translatePage(hanBlocks) as PageTranslation.Success

        // The prompt carries exactly the provided sheet …
        assertEquals(1, api.requestBodies.size)
        val body = api.requestBodies.single()
        assertTrue(body, body.contains("Han Ji-woo = هان جيو"))
        // … and the repair applies the table that produced it.
        assertEquals("هان جيو سحب سيفه.", result.blocks.first { it.id == 1 }.arabic)
    }

    @Test
    fun aSeriesSpellingWinsOverThePagePlanner() {
        Glossary.refresh("", true)
        // Premise (chain D-A): the page planner ALONE spells this name differently, so the two
        // authorities really can disagree.
        val pageLocal = NameMemory.plan(hanBlocks.map { it.english }).table
            .entries.first { NameMemory.looseKey(it.key) == "hanjiwoo" }.value
        assertEquals("هان جي وو", pageLocal)

        val resolved = NameSheet("Han Ji-woo = هان جيو", mapOf("Han Ji-woo" to "هان جيو"))
        val api = FakeGeminiApi(
            result = GeminiHttpResult(200, okBody(1 to "Han Ji-woo سحب سيفه.", 2 to "لم يقل شيئا."))
        )

        val result = GeminiTranslator(api, nameSheetProvider = { resolved })
            .translatePage(hanBlocks) as PageTranslation.Success
        val arabic = result.blocks.first { it.id == 1 }.arabic

        assertEquals("the resolved series spelling must win", "هان جيو سحب سيفه.", arabic)
        assertFalse("the page-local transliteration must not appear", arabic.contains("هان جي وو"))
    }

    @Test
    fun aLockedSpellingIsNeverReplacedByAPageDetection() {
        Glossary.refresh("", true)
        val songBlocks = listOf(
            TranslationBlock(1, "Song Lan drew his sword."),
            TranslationBlock(2, "Xiao Xingchen said nothing.")
        )
        // The page planner would use the built-in spelling; the reader locked a different one.
        val builtIn = NameMemory.plan(songBlocks.map { it.english }).table
            .entries.first { NameMemory.looseKey(it.key) == "songlan" }.value
        assertEquals("سونغ لان", builtIn)
        val resolved = NameSheet("Song Lan = سونغ لآن", mapOf("Song Lan" to "سونغ لآن"))
        val api = FakeGeminiApi(
            result = GeminiHttpResult(200, okBody(1 to "Song Lan سحب سيفه.", 2 to "لم يقل شيئا."))
        )

        val result = GeminiTranslator(api, nameSheetProvider = { resolved })
            .translatePage(songBlocks) as PageTranslation.Success
        val arabic = result.blocks.first { it.id == 1 }.arabic

        assertEquals("سونغ لآن سحب سيفه.", arabic)
        assertFalse("the built-in spelling must not displace the locked one", arabic.contains("سونغ لان"))
    }

    @Test
    fun aPageNameThatTheSeriesDoesNotKnowIsStillNamed() {
        Glossary.refresh("", true)
        val page = listOf(
            TranslationBlock(1, "Baek Seol drew his sword."),
            TranslationBlock(2, "Wei Wuxian said nothing.")
        )
        // The series memory does not know this character; the resolved table still carries the
        // page's own detection, and both halves must use it.
        val resolved = NameSheet("Baek Seol = بيك سول", mapOf("Baek Seol" to "بيك سول"))
        val api = FakeGeminiApi(
            result = GeminiHttpResult(200, okBody(1 to "Baek Seol سحب سيفه.", 2 to "لم يقل شيئا."))
        )

        val result = GeminiTranslator(api, nameSheetProvider = { resolved })
            .translatePage(page) as PageTranslation.Success

        val body = api.requestBodies.single()
        assertTrue(body, body.contains("Baek Seol = بيك سول"))
        assertEquals("بيك سول سحب سيفه.", result.blocks.first { it.id == 1 }.arabic)
    }

    @Test
    fun noSeriesSelectedKeepsThePageLocalBehaviour() {
        Glossary.refresh("", true)
        val page = listOf(
            TranslationBlock(1, "Wei Wuxian said nothing."),
            TranslationBlock(2, "Lan Wangji drew his sword.")
        )
        val reply = GeminiHttpResult(
            200,
            okBody(1 to "Wei Wuxian لم يقل شيئا.", 2 to "Lan Wangji سحب سيفه.")
        )
        // v0.11.0 / P0-A: the page-local plan supplies BOTH halves. Pin that exact sheet.
        val expectedSheet = NameMemory.sheet(NameMemory.plan(page.map { it.english }), limit = 20)
        assertTrue(expectedSheet, expectedSheet.contains("Wei Wuxian = وي ووشيان"))

        // (a) The pre-P1 wiring: no provider at all → the page-local plan does both jobs.
        val withoutProvider = FakeGeminiApi(result = reply)
        val a = GeminiTranslator(withoutProvider).translatePage(page) as PageTranslation.Success
        assertEquals("وي ووشيان لم يقل شيئا.", a.blocks.first { it.id == 1 }.arabic)
        assertTrue(
            withoutProvider.requestBodies.single(),
            withoutProvider.requestBodies.single().contains(expectedSheet)
        )

        // (b) An unassigned session resolves nothing on this page → identical request and output.
        val withEmptyProvider = FakeGeminiApi(result = reply)
        val b = GeminiTranslator(withEmptyProvider, nameSheetProvider = { NameSheet.EMPTY })
            .translatePage(page) as PageTranslation.Success
        assertEquals(a.blocks.first { it.id == 1 }.arabic, b.blocks.first { it.id == 1 }.arabic)
        assertEquals(
            "an empty resolved sheet must fall back to exactly the old page-local path",
            withoutProvider.requestBodies.single(),
            withEmptyProvider.requestBodies.single()
        )
    }

    @Test
    fun p2FixesStillHoldAfterTheSeamChange() {
        Glossary.refresh("", true)
        // P2-18 — an everyday English word is still not a name, through the new seam either.
        val page = listOf(
            TranslationBlock(1, "The Sun was hot and the road was long."),
            TranslationBlock(2, "Long ago, the road was long.")
        )
        val plan = NameMemory.plan(page.map { it.english })
        assertTrue(
            "no Sun/Long may be pinned: ${plan.table}",
            plan.table.keys.none { it.equals("Sun", true) || it.equals("Long", true) }
        )
        val resolvedByPage = NameSheet(NameMemory.sheet(plan, limit = 20), plan.table)
        assertFalse(resolvedByPage.sheet.contains("Sun ="))
        assertFalse(resolvedByPage.sheet.contains("Long ="))

        val api = FakeGeminiApi(
            result = GeminiHttpResult(200, okBody(1 to "كانت الشمس حارة والطريق طويلا.", 2 to "منذ زمن بعيد."))
        )
        GeminiTranslator(api).translatePage(page)
        val body = api.requestBodies.single()
        assertFalse("no ambiguous word may reach the prompt", body.contains("Sun = "))
        assertFalse("no ambiguous word may reach the prompt", body.contains("Long = "))

        // P2-19 — the live glossary rule is `accepts`: a bare ambiguous word is left alone …
        assertEquals(
            "Sun was in his eyes and the road was long.",
            Glossary.prepare("Sun was in his eyes and the road was long.")
        )
        // … while P2-17's retry format is asserted by the 400 tests above in this class.
    }

    // ------------------------------------------------------------------
    // P2-04 end to end: what the reader sees per bubble id.
    // ------------------------------------------------------------------

    @Test
    fun positionalReplyWithALabelStillMapsEveryBubbleEndToEnd() {
        val api = FakeGeminiApi(
            result = GeminiHttpResult(200, jsonEnvelope("الترجمة:\nمرحبا\nخذ يدي"))
        )
        val result = translatorWith(api).translatePage(blocks) as PageTranslation.Success
        assertEquals("مرحبا", result.blocks.first { it.id == 1 }.arabic)
        assertEquals("خذ يدي", result.blocks.first { it.id == 2 }.arabic)
    }

    @Test
    fun ambiguousPositionalReplyIsRejectedInsteadOfShifted() {
        val api = FakeGeminiApi(
            result = GeminiHttpResult(200, jsonEnvelope("مرحبا\nخذ يدي\nسطر ثالث زائد"))
        )
        val result = translatorWith(api).translatePage(blocks) as PageTranslation.Failure
        assertEquals(FailureReason.PARSE_ERROR, result.reason)
        // No re-ask is possible (every bubble is missing), so the page fails honestly
        // instead of painting a shifted translation.
        assertEquals(1, api.requestBodies.size)
    }
}
