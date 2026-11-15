package com.manhwalens.app.translate.gemini

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiImageWireTest {

    private fun modelsBody(): String = """
        {"models": [
          {"name": "models/gemini-3.8-flash", "displayName": "Gemini 3.8 Flash", "supportedGenerationMethods": ["generateContent"]},
          {"name": "models/gemini-3.8-flash-image", "displayName": "Gemini 3.8 Flash Image", "supportedGenerationMethods": ["generateContent"]},
          {"name": "models/gemini-3.8-image-preview", "displayName": "Gemini 3.8 Image Preview", "supportedGenerationMethods": ["generateContent"]}
        ]}
    """

    @Test
    fun imageCapableModels_keepsOnlyImageModels() {
        val list = GeminiWire.parseModelsBody(modelsBody())
        val imgs = GeminiWire.imageCapableModels(list)
        assertEquals(setOf("models/gemini-3.8-flash-image", "models/gemini-3.8-image-preview"),
            imgs.map { it.name }.toSet())
    }

    @Test
    fun imageCapableModels_noneMeansEmptyNotCrash() {
        assertEquals(emptyList<GeminiWire.GeminiModelInfo>(),
            GeminiWire.imageCapableModels(GeminiWire.parseModelsBody("{\"models\": []}")))
    }

    @Test
    fun shortModelId_stripsResourcePrefix() {
        assertEquals("gemini-3.8-flash-image", GeminiWire.shortModelId("models/gemini-3.8-flash-image"))
        assertEquals("gemini-3.8-flash-image", GeminiWire.shortModelId("gemini-3.8-flash-image"))
    }

    @Test
    fun buildImageEditBody_placesImageFirstAndKeepsKeyOut() {
        val body = GeminiWire.buildImageEditBody("QUJD", "image/jpeg", GeminiWire.imageEditInstruction("مرحباً"))
        assertTrue(body.indexOf("inlineData") < body.indexOf("text"))
        assertTrue(body.contains("QUJD"))
        assertTrue(body.contains("responseModalities"))
        assertTrue(body.contains("مرحباً"))
        assertTrue(!body.contains("x-goog-api-key"))
    }

    @Test
    fun extractInlineImage_findsInlineData() {
        val resp = """{"candidates":[{"content":{"parts":[
            {"text":"sure!"},
            {"inlineData":{"mimeType":"image/png","data":"aGVsbG8="}}
        ]}}]}"""
        val out = GeminiWire.extractInlineImage(resp)
        assertNotNull(out)
        assertEquals("image/png", out!!.first)
        assertEquals("aGVsbG8=", out.second)
    }

    @Test
    fun extractInlineImage_snakeCaseAndRefusal() {
        val snake = """{"candidates":[{"content":{"parts":[{"inline_data":{"mimeType":"image/jpeg","data":"eA=="}}]}}]}"""
        assertEquals("image/jpeg" to "eA==", GeminiWire.extractInlineImage(snake))
        assertNull(GeminiWire.extractInlineImage("""{"promptFeedback":{"blockReason":"SAFETY"}}"""))
        assertNull(GeminiWire.extractInlineImage("""{"candidates":[{"content":{"parts":[{"text":"no image"}]}}]}"""))
        assertNull(GeminiWire.extractInlineImage("not json"))
    }
}
