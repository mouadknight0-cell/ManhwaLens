package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Test

/** Fake engine: records every call, replies with "AR:" + input (or fails on demand). */
class FakeTranslator(
    var failWith: PageTranslation.Failure? = null,
    var dropIds: Set<Int> = emptySet() // simulate a model that loses blocks
) : Translator {
    override val engineId = "fake_engine"
    var calls: Int = 0
        private set
    val batchSizes = ArrayList<Int>()
    val receivedBlocks = ArrayList<List<TranslationBlock>>()

    override fun translatePage(blocks: List<TranslationBlock>): PageTranslation {
        calls++
        batchSizes += blocks.size
        receivedBlocks += blocks
        failWith?.let { return it }
        return PageTranslation.Success(
            blocks = blocks
                .filterNot { it.id in dropIds }
                .map { TranslatedBlock(it.id, it.english, "AR:${it.english}") },
            engineId = engineId,
            elapsedMs = 1,
            cacheHits = 0,
            cacheMisses = 0
        )
    }
}

private class FakeRemoteTranslator(private val output: String) : Translator {
    override val engineId = "gemini"
    var calls = 0
    var failWith: PageTranslation.Failure? = null

    override fun translatePage(blocks: List<TranslationBlock>): PageTranslation {
        calls++
        failWith?.let { return it }
        return PageTranslation.Success(
            blocks = blocks.map { TranslatedBlock(it.id, it.english, output) },
            engineId = engineId,
            elapsedMs = 1,
            cacheHits = 0,
            cacheMisses = blocks.size
        )
    }
}

class CachingTranslatorTest {

    private fun blocks(vararg pairs: Pair<Int, String>) =
        pairs.map { TranslationBlock(it.first, it.second) }

    @Test
    fun allMissesGoThroughEngineInOneBatch() {
        val fake = FakeTranslator()
        val caching = CachingTranslator(fake, TranslationCache())
        val result = caching.translatePage(blocks(1 to "Hello", 2 to "World")) as PageTranslation.Success
        assertEquals(1, fake.calls)
        assertEquals(2, fake.batchSizes.single())
        assertEquals(listOf("AR:Hello", "AR:World"), result.blocks.map { it.arabic })
        assertEquals(0, result.cacheHits)
        assertEquals(2, result.cacheMisses)
    }

    @Test
    fun secondIdenticalPageIsServedFromCacheWithoutEngineCall() {
        val fake = FakeTranslator()
        val cache = TranslationCache()
        val caching = CachingTranslator(fake, cache)
        caching.translatePage(blocks(1 to "Hello", 2 to "World"))
        val second = caching.translatePage(blocks(1 to "Hello", 2 to "World")) as PageTranslation.Success
        assertEquals(1, fake.calls) // no new engine call
        assertEquals(2, second.cacheHits)
        assertEquals(0, second.cacheMisses)
        assertEquals(listOf("AR:Hello", "AR:World"), second.blocks.map { it.arabic })
    }

    @Test
    fun partialHitOnlySendsMissingBlocks() {
        val fake = FakeTranslator()
        val cache = TranslationCache()
        val caching = CachingTranslator(fake, cache)
        caching.translatePage(blocks(1 to "Hello")) // primes cache
        val second = caching.translatePage(blocks(1 to "Hello", 2 to "New line")) as PageTranslation.Success
        assertEquals(listOf(2), fake.receivedBlocks.last().map { it.id })
        assertEquals(1, second.cacheHits)
        assertEquals(1, second.cacheMisses)
        assertEquals(listOf("AR:Hello", "AR:New line"), second.blocks.map { it.arabic })
    }

    @Test
    fun engineFailureIsPropagatedAndNothingIsCached() {
        val fake = FakeTranslator(
            failWith = PageTranslation.Failure(FailureReason.NO_INTERNET, "offline", "fake_engine")
        )
        val cache = TranslationCache()
        val caching = CachingTranslator(fake, cache)
        val result = caching.translatePage(blocks(1 to "Hello")) as PageTranslation.Failure
        assertEquals(FailureReason.NO_INTERNET, result.reason)
        assertEquals(0, cache.size())
        // Retry must reach the engine again (nothing stale cached).
        fake.failWith = null
        val retry = caching.translatePage(blocks(1 to "Hello")) as PageTranslation.Success
        assertEquals(2, fake.calls)
        assertEquals("AR:Hello", retry.blocks.single().arabic)
    }

    @Test
    fun blankBlocksNeverReachEngineAndEchoEmpty() {
        val fake = FakeTranslator()
        val caching = CachingTranslator(fake, TranslationCache())
        val result = caching.translatePage(blocks(1 to "   ", 2 to "Real text")) as PageTranslation.Success
        assertEquals(listOf(2), fake.receivedBlocks.single().map { it.id })
        assertEquals("", result.blocks.first { it.id == 1 }.arabic)
        assertEquals("AR:Real text", result.blocks.first { it.id == 2 }.arabic)
    }

    @Test
    fun staleRemoteEnglishEchoInCacheIsRetriedInsteadOfServed() {
        val cache = TranslationCache()
        cache.put("gemini", "Hello!", "Hello!") // simulated pre-guard cache entry
        val remote = FakeRemoteTranslator("مرحبًا")
        val result = CachingTranslator(remote, cache).translatePage(blocks(1 to "Hello!")) as PageTranslation.Success

        assertEquals(1, remote.calls)
        assertEquals("مرحبًا", result.blocks.single().arabic)
        assertEquals(0, result.cacheHits)
        assertEquals(1, result.cacheMisses)
        assertEquals("مرحبًا", cache.get("gemini", "Hello!"))
    }

    @Test
    fun invalidMemoryHitAlsoPurgesItsPersistentCopyWhenRemoteRetryFails() {
        val file = java.io.File.createTempFile("translation-cache", ".json").apply { delete() }
        try {
            val cache = TranslationCache().apply { put("gemini", "Hello!", "Hello!") }
            val key = cache.key("gemini", "Hello!")
            val persistent = PersistentTranslationCache(file).apply { put(key, "Hello!") }
            val remote = FakeRemoteTranslator("ignored").apply {
                failWith = PageTranslation.Failure(FailureReason.NO_INTERNET, "offline", engineId)
            }

            val result = CachingTranslator(remote, cache, persistent)
                .translatePage(blocks(1 to "Hello!")) as PageTranslation.Failure

            assertEquals(FailureReason.NO_INTERNET, result.reason)
            assertEquals(null, persistent.get(key))
        } finally {
            file.delete()
        }
    }

    @Test
    fun stalePersistentRemoteEnglishEchoIsRemovedBeforeRetry() {
        val file = java.io.File.createTempFile("translation-cache", ".json").apply { delete() }
        try {
            val key = TranslationCache().key("gemini", "Hello!")
            val persistent = PersistentTranslationCache(file)
            persistent.put(key, "Hello!")
            val remote = FakeRemoteTranslator("مرحبًا")
            val result = CachingTranslator(remote, TranslationCache(), persistent)
                .translatePage(blocks(1 to "Hello!")) as PageTranslation.Success

            assertEquals(1, remote.calls)
            assertEquals("مرحبًا", result.blocks.single().arabic)
            assertEquals("مرحبًا", persistent.get(key))
        } finally {
            file.delete()
        }
    }

    @Test
    fun remoteSuccessWithInvalidOutputIsNotCached() {
        val cache = TranslationCache()
        val remote = FakeRemoteTranslator("Hello!")
        val result = CachingTranslator(remote, cache).translatePage(blocks(1 to "Hello!")) as PageTranslation.Failure

        assertEquals(FailureReason.PARSE_ERROR, result.reason)
        assertEquals(0, cache.size())
    }

    @Test
    fun implausiblyShortRemoteTranslationIsRejectedAndNotCached() {
        val file = java.io.File.createTempFile("translation-cache", ".json").apply { delete() }
        try {
            val source = "I remember the night when you came to this village and told everyone that the old king had returned from the northern mountains after many years away."
            val cache = TranslationCache()
            val key = cache.key("gemini", source)
            val persistent = PersistentTranslationCache(file)
            val remote = FakeRemoteTranslator("نعم")

            val result = CachingTranslator(remote, cache, persistent)
                .translatePage(blocks(1 to source)) as PageTranslation.Failure

            assertEquals(FailureReason.PARSE_ERROR, result.reason)
            assertEquals(0, cache.size())
            assertEquals(null, persistent.get(key))
        } finally {
            file.delete()
        }
    }

    @Test
    fun appliesPinnedCharacterNamesBeforeTheRemoteQualityGuard() {
        val remote = FakeRemoteTranslator("انتظر يا Will، أريدك أن تستمع لما حدث بعد المعركة")
        val caching = CachingTranslator(
            delegate = remote,
            cache = TranslationCache(),
            pinnedOverride = { _, arabic -> arabic.replace("Will", "ويل") }
        )
        val source = "Will, I need you to listen to what happened after the battle yesterday."

        val result = caching.translatePage(blocks(1 to source)) as PageTranslation.Success

        assertEquals("انتظر يا ويل، أريدك أن تستمع لما حدث بعد المعركة", result.blocks.single().arabic)
    }

    @Test
    fun emptyInputReturnsEmptyInputFailure() {
        val fake = FakeTranslator()
        val caching = CachingTranslator(fake, TranslationCache())
        val result = caching.translatePage(emptyList()) as PageTranslation.Failure
        assertEquals(FailureReason.EMPTY_INPUT, result.reason)
        assertEquals(0, fake.calls)
    }

    @Test
    fun originalBlockOrderIsPreservedRegardlessOfEngineOutputOrder() {
        val fake = FakeTranslator()
        val caching = CachingTranslator(fake, TranslationCache())
        val input = blocks(3 to "three", 1 to "one", 2 to "two")
        val result = caching.translatePage(input) as PageTranslation.Success
        assertEquals(listOf(3, 1, 2), result.blocks.map { it.id })
    }
}
