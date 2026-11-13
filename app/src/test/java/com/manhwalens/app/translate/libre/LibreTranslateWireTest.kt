package com.manhwalens.app.translate.libre

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibreTranslateWireTest {

    @Test
    fun acceptsBaseOrFullTranslateUrlAndAppendsEndpointExactlyOnce() {
        assertEquals(
            "https://translate.example/translate",
            LibreTranslateWire.translateEndpoint("https://translate.example/")
        )
        assertEquals(
            "https://translate.example/mt/translate",
            LibreTranslateWire.translateEndpoint("https://translate.example/mt")
        )
        assertEquals(
            "https://translate.example/mt/translate",
            LibreTranslateWire.translateEndpoint("https://translate.example/mt/translate")
        )
    }

    @Test
    fun rejectsCleartextCredentialQueryAndInvalidUrls() {
        assertNull(LibreTranslateWire.translateEndpoint("http://translate.example"))
        assertNull(LibreTranslateWire.translateEndpoint("https://user:pass@translate.example"))
        assertNull(LibreTranslateWire.translateEndpoint("https://translate.example?api_key=secret"))
        assertNull(LibreTranslateWire.translateEndpoint("not a url"))
        assertNull(LibreTranslateWire.translateEndpoint(""))
    }

    @Test
    fun requestUsesOneEnglishToArabicBatchAndKeepsApiKeyOutOfUrl() {
        val body = JSONObject(
            LibreTranslateWire.buildRequestBody(listOf("Hello!", "Take my hand."), "private-key")
        )
        assertEquals("en", body.getString("source"))
        assertEquals("ar", body.getString("target"))
        assertEquals("text", body.getString("format"))
        assertEquals("private-key", body.getString("api_key"))
        assertEquals(JSONArray(listOf("Hello!", "Take my hand.")).toString(), body.getJSONArray("q").toString())
        assertTrue(LibreTranslateWire.translateEndpoint("https://translate.example").orEmpty().indexOf("private-key") < 0)
    }

    @Test
    fun parsesArrayResponsesAndSingleStringResponses() {
        assertEquals(
            listOf("مرحبًا", "خذ يدي"),
            LibreTranslateWire.parseTranslatedTexts(
                """{"translatedText":["مرحبًا","خذ يدي"]}""",
                2
            )
        )
        assertEquals(
            listOf("مرحبًا"),
            LibreTranslateWire.parseTranslatedTexts("""{"translatedText":"مرحبًا"}""", 1)
        )
        assertNull(LibreTranslateWire.parseTranslatedTexts("""{"translatedText":["مرحبًا"]}""", 2))
        assertNull(LibreTranslateWire.parseTranslatedTexts("not json", 1))
    }

    @Test
    fun errorDetailsRedactTheConfiguredKey() {
        val body = """{"error":"bad API key: private-key"}"""
        val message = LibreTranslateWire.errorMessage(body, "private-key")
        assertTrue(message.contains("[redacted]"))
        assertTrue(!message.contains("private-key"))
    }

    @Test
    fun endpointChangesProduceStableNonSecretCacheTags() {
        val first = LibreTranslateWire.endpointCacheTag("https://translate.example")
        assertEquals(first, LibreTranslateWire.endpointCacheTag("https://translate.example/translate"))
        assertTrue(first != LibreTranslateWire.endpointCacheTag("https://other.example"))
    }
}
