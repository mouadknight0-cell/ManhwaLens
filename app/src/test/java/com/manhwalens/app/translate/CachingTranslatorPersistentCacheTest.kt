package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CachingTranslatorPersistentCacheTest {

    @Test
    fun sourceExactDiskCorrectionIsWrittenBackAndSurvivesReload() {
        val file = File.createTempFile("translation-cache", ".json").apply { delete() }
        try {
            val source = "KRAK!"
            val cache = TranslationCache()
            val key = cache.key("ml_kit_en_ar", source)
            val firstDiskCache = PersistentTranslationCache(file)
            // A value persisted by an older build before the exact-source SFX repair existed.
            firstDiskCache.put(key, "كراك")

            var engineCalls = 0
            val engine = object : Translator {
                override val engineId = "ml_kit_en_ar"

                override fun translatePage(blocks: List<TranslationBlock>): PageTranslation {
                    engineCalls++
                    return PageTranslation.Failure(
                        FailureReason.UNKNOWN,
                        "The engine must not be called for an exact disk-cache hit",
                        engineId
                    )
                }
            }

            val first = CachingTranslator(
                delegate = engine,
                cache = TranslationCache(),
                persistent = firstDiskCache
            ).translatePage(listOf(TranslationBlock(1, source)))

            assertTrue("the exact disk hit should be served", first is PageTranslation.Success)
            assertEquals("طَق!", (first as PageTranslation.Success).blocks.single().arabic)
            assertEquals(0, engineCalls)
            assertEquals("طَق!", firstDiskCache.get(key))

            // Simulate a new process: only the persistent cache survives.
            val reloadedDiskCache = PersistentTranslationCache(file)
            assertEquals("طَق!", reloadedDiskCache.get(key))
            val second = CachingTranslator(
                delegate = engine,
                cache = TranslationCache(),
                persistent = reloadedDiskCache
            ).translatePage(listOf(TranslationBlock(1, source)))

            assertTrue("the corrected disk value should survive process recreation", second is PageTranslation.Success)
            assertEquals("طَق!", (second as PageTranslation.Success).blocks.single().arabic)
            assertEquals(0, engineCalls)
        } finally {
            file.delete()
        }
    }

    @Test
    fun legacyDiskHitUsesFreshOutputSanitizerAndWritesCleanValueBack() {
        val file = File.createTempFile("legacy-translation-cache", ".json").apply { delete() }
        try {
            val source = "Are you okay?"
            val cache = TranslationCache()
            val key = cache.key("ml_kit_en_ar", source)
            val disk = PersistentTranslationCache(file)
            // Simulates a pre-sanitizer value retained across an application update.
            disk.put(key, "الترجمة: هل أنت بخير ?")

            var engineCalls = 0
            val engine = object : Translator {
                override val engineId = "ml_kit_en_ar"

                override fun translatePage(blocks: List<TranslationBlock>): PageTranslation {
                    engineCalls++
                    return PageTranslation.Failure(FailureReason.UNKNOWN, "Unexpected cache miss", engineId)
                }
            }
            val traces = ArrayList<TranslationPairTrace>()
            val result = CachingTranslator(
                delegate = engine,
                cache = TranslationCache(),
                persistent = disk,
                onTranslationPair = { traces += it }
            ).translatePage(listOf(TranslationBlock(2, source)))

            assertTrue(result is PageTranslation.Success)
            assertEquals("هل أنت بخير؟", (result as PageTranslation.Success).blocks.single().arabic)
            assertEquals(0, engineCalls)
            assertEquals("هل أنت بخير؟", disk.get(key))
            assertEquals("الترجمة: هل أنت بخير ?", traces.single().candidateArabic)
            assertEquals("هل أنت بخير؟", traces.single().finalArabic)
            assertEquals("served from persistent exact cache", traces.single().detail)
        } finally {
            file.delete()
        }
    }

}
