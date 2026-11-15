package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class PersistentTranslationCacheTest {

    @Test
    fun removeDeletesEntryAndPersistsTheChange() {
        val file = File.createTempFile("persistent-cache", ".json").apply { delete() }
        try {
            val first = PersistentTranslationCache(file)
            first.put("gemini|default|hello", "Hello")
            first.remove("gemini|default|hello")

            assertEquals(null, first.get("gemini|default|hello"))
            assertEquals(0, PersistentTranslationCache(file).size())
        } finally {
            file.delete()
        }
    }
}
