package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationCacheTest {

    @Test
    fun removeUsesTheSameNamespacedNormalizedKeyAsGetAndPut() {
        val cache = TranslationCache()
        cache.put("gemini", "  Hello   world! ", "مرحبًا")

        cache.remove("gemini", "hello world!")

        assertEquals(null, cache.get("gemini", "Hello world!"))
        assertEquals(0, cache.size())
    }
}
