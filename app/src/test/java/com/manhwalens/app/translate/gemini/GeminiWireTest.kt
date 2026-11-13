package com.manhwalens.app.translate.gemini

import com.manhwalens.app.translate.TranslationBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiWireTest {

    private val blocks = listOf(
        TranslationBlock(1, "Hello!"),
        TranslationBlock(2, "KRAK! The wall crumbles.")
    )

    @Test
    fun requestBodyContainsNumberedBlocksAndIds() {
        val body = GeminiWire.buildRequestBody(blocks)
        assertTrue(body.contains("[1] Hello!"))
        assertTrue(body.contains("[2] KRAK! The wall crumbles."))
        assertTrue(body.contains("generationConfig"))
        assertTrue(body.contains("systemInstruction"))
    }

    @Test
    fun requestBodyNeverContainsAnApiKey() {
        val body = GeminiWire.buildRequestBody(blocks)
        assertFalse("Key must travel in the HTTP header only", body.contains("x-goog-api-key"))
        assertFalse(body.contains("AIza"))
    }

    @Test
    fun extractTextReadsCandidatesPayload() {
        val response = """{"candidates":[{"content":{"parts":[{"text":"[1] مرحبا\n[2] كراك"}]}}]}"""
        assertEquals("[1] مرحبا\n[2] كراك", GeminiWire.extractText(response))
    }

    @Test
    fun extractTextReturnsNullOnGarbage() {
        assertNull(GeminiWire.extractText("not json"))
        assertNull(GeminiWire.extractText("""{"candidates":[]}"""))
        assertNull(GeminiWire.extractText("""{"usageMetadata":{}}"""))
    }

    @Test
    fun parseNumberedLinesParsesArabicLines() {
        val parsed = GeminiWire.parseNumberedLines(
            "[1] مرحبا بك\n[2] كراك! الجدار ينهار"
        )
        assertNotNull(parsed)
        assertEquals("مرحبا بك", parsed!![1])
        assertEquals("كراك! الجدار ينهار", parsed[2])
    }

    @Test
    fun parseNumberedLinesIgnoresStrayTextAndReturnsNullWhenEmpty() {
        assertNull(GeminiWire.parseNumberedLines("no numbered lines here"))
        val parsed = GeminiWire.parseNumberedLines("intro noise\n[3] سطر واحد فقط\ntrailing noise")
        assertEquals(mapOf(3 to "سطر واحد فقط"), parsed)
    }

    @Test
    fun userPromptNumbersBlocksExactly() {
        assertEquals("[1] Hello!\n[2] KRAK! The wall crumbles.", GeminiWire.userPrompt(blocks))
    }

    @Test
    fun promptForResolvesKnownNamesAndAppendsTheGlossary() {
        com.manhwalens.app.translate.Glossary.refresh("", true)
        val prompt = GeminiWire.promptFor(listOf(TranslationBlock(1, "Wei Wuxian, come here.")))
        // the bubble itself is Arabic before the model sees it
        val blockLine = prompt.substringBefore("\n\n")
        assertTrue(blockLine, blockLine.contains("وي ووشيان"))
        assertFalse(blockLine, blockLine.contains("Wuxian"))
        assertTrue(prompt, prompt.contains(GeminiWire.GLOSSARY_HEADER))
        assertTrue(prompt, prompt.contains("Lan Wangji"))
    }

    @Test
    fun systemInstructionRequestsOneSentencePerBubble_andKeepsGlossarySpelling() {
        val body = GeminiWire.buildRequestBody(blocks)
        assertTrue(body.contains("exactly as written"))
        assertTrue(body.contains("Spell every name the same way"))
    }

    @Test
    fun systemInstructionRequestsModernStandardArabic() {
        val body = GeminiWire.buildRequestBody(blocks)
        // The system instruction must ask for Modern Standard Arabic (not dialect)
        // and continuous sentences per bubble (not fragmented per line).
        assertTrue(body.contains("Modern Standard Arabic"))
        assertTrue(body.contains("continuous sentence"))
        assertTrue(body.contains("names unchanged"))
    }

    @Test
    fun oneRequestPerPage_allBlocksInSingleBody() {
        val many = (1..20).map { TranslationBlock(it, "Bubble number $it here") }
        val body = GeminiWire.buildRequestBody(many)
        // All 20 blocks appear in exactly one request body (batched, not split).
        (1..20).forEach { i -> assertTrue("block $i missing from batched request", body.contains("[$i] Bubble number $i")) }
    }

    // ------------------------------------------------------------------
    // P0-A: the page name sheet must reach the FIRST request, on BOTH
    // body formats (jsonReply = true and the legacy numbered-lines body).
    // ------------------------------------------------------------------

    /** The exact shape [NameMemory.sheet] produces: "Name = عربي; Name2 = عربي2". */
    private val pageSheet = "Lan Wangji = لان وانغجي; Wei Wuxian = وي ووشيان"

    private val namedBlocks = listOf(
        TranslationBlock(1, "Lan Wangji drew his sword."),
        TranslationBlock(2, "Wei Wuxian said nothing.")
    )

    @Test
    fun legacyRequestBodyCarriesEveryNameSheetLine() {
        // The production default is the legacy body (jsonReply = false).
        val body = GeminiWire.buildRequestBody(
            namedBlocks, GeminiWire.THINKING_LEVEL, pageSheet, jsonReply = false
        )
        pageSheet.split("; ").forEach { line ->
            assertTrue("legacy body is missing the sheet line '$line'", body.contains(line))
        }
        // The sheet is the ONE glossary block: never the reader's whole glossary as well.
        assertEquals(1, body.split(GeminiWire.GLOSSARY_HEADER).size - 1)
    }

    @Test
    fun jsonRequestBodyCarriesEveryNameSheetLine() {
        val body = GeminiWire.buildRequestBody(
            namedBlocks, GeminiWire.THINKING_LEVEL, pageSheet, jsonReply = true
        )
        pageSheet.split("; ").forEach { line ->
            assertTrue("JSON body is missing the sheet line '$line'", body.contains(line))
        }
        assertTrue(body.contains("\"responseMimeType\":\"application/json\""))
    }

    @Test
    fun bothBodyFormatsAgreeOnTheNamesTheySend() {
        val legacy = GeminiWire.buildRequestBody(
            namedBlocks, GeminiWire.THINKING_LEVEL, pageSheet, jsonReply = false
        )
        val json = GeminiWire.buildRequestBody(
            namedBlocks, GeminiWire.THINKING_LEVEL, pageSheet, jsonReply = true
        )
        // Same prompt text on both paths — a name spelling must not depend on the format.
        // Compared line by line: inside the JSON body newlines are escaped, single lines are not.
        val promptLines = GeminiWire.promptFor(namedBlocks, pageSheet)
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
        assertTrue("prompt should carry the sheet", promptLines.any { it.contains(pageSheet) })
        promptLines.forEach { line ->
            assertTrue("legacy body is missing prompt line '$line'", legacy.contains(line))
            assertTrue("JSON body is missing prompt line '$line'", json.contains(line))
        }
    }

    @Test
    fun blankNameSheetFallsBackWithoutMalformedPromptContent() {
        com.manhwalens.app.translate.Glossary.refresh("", true)
        // Name-free bubbles: this test must observe the sheet logic, not [Glossary.prepare].
        val plain = listOf(TranslationBlock(1, "Hello!"), TranslationBlock(2, "Take my hand."))
        val withBlank = GeminiWire.buildRequestBody(plain, GeminiWire.THINKING_LEVEL, "")
        val oneArg = GeminiWire.buildRequestBody(plain)
        // Backwards compatible: a blank sheet is exactly the old behaviour.
        assertEquals(oneArg, withBlank)
        assertFalse("a blank sheet must not leak a null into the prompt", withBlank.contains("null"))
        assertFalse("the prompt text must never be empty", withBlank.contains("\"text\":\"\""))
        assertTrue(withBlank.split(GeminiWire.GLOSSARY_HEADER).size - 1 <= 1)
        // The bubbles are still numbered exactly as before.
        assertTrue(withBlank.contains("[1] Hello!"))
        assertTrue(withBlank.contains("[2] Take my hand."))
    }

    @Test
    fun nullThinkingLevelOmitsThinkingConfigButKeepsTheSheet() {
        val body = GeminiWire.buildRequestBody(
            namedBlocks, null, pageSheet, jsonReply = false
        )
        assertFalse("null thinkingLevel must not emit a thinkingConfig", body.contains("thinkingConfig"))
        assertFalse(body, body.contains("null"))
        assertTrue(body, body.contains(pageSheet))
    }
}
