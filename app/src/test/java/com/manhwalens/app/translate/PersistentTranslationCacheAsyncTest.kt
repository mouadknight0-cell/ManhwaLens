package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * v0.13.0 — putAllAsync keeps the disk write off the pipeline thread while reads stay
 * synchronous; flush() makes queued writes durable; clear() never resurrects entries
 * from a pending background save.
 */
class PersistentTranslationCacheAsyncTest {

    private fun tempFile(): File = File.createTempFile("persistent-cache-async", ".json").apply { delete() }

    @Test
    fun asyncPutIsVisibleImmediatelyAndDurableAfterFlush() {
        val file = tempFile()
        try {
            val cache = PersistentTranslationCache(file)
            cache.putAllAsync(mapOf("gemini|default|hello" to "مرحبا", "gemini|default|world" to "عالم"))
            // In-memory LRU is updated synchronously — no waiting for the disk.
            assertEquals("مرحبا", cache.get("gemini|default|hello"))
            cache.flush()
            // A fresh instance reads it back from disk.
            val reloaded = PersistentTranslationCache(file)
            assertEquals("مرحبا", reloaded.get("gemini|default|hello"))
            assertEquals("عالم", reloaded.get("gemini|default|world"))
        } finally {
            file.delete()
        }
    }

    @Test
    fun clearAfterAsyncPutDoesNotResurrectEntries() {
        val file = tempFile()
        try {
            val cache = PersistentTranslationCache(file)
            cache.putAllAsync(mapOf("gemini|default|hello" to "مرحبا"))
            cache.clear() // flushes the queued save first, then deletes the file
            assertNull(cache.get("gemini|default|hello"))
            assertEquals(0, PersistentTranslationCache(file).size())
        } finally {
            file.delete()
        }
    }

    @Test
    fun syncPutAllStillWorks() {
        val file = tempFile()
        try {
            val cache = PersistentTranslationCache(file)
            cache.putAll(mapOf("gemini|default|hello" to "مرحبا"))
            assertEquals("مرحبا", PersistentTranslationCache(file).get("gemini|default|hello"))
        } finally {
            file.delete()
        }
    }
}
