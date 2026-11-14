package com.manhwalens.app.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineTranslationPolicyTest {

    @Test
    fun offlineModeAlwaysForcesLocalEngineRegardlessOfSelection() {
        for (engine in TranslationEngine.values()) {
            assertTrue("offline must force local for $engine", OfflineTranslationPolicy.mustUseLocal(true))
        }
        assertFalse(OfflineTranslationPolicy.mustUseLocal(false))
    }

    @Test
    fun offlineModeBlocksEveryRemoteUpgrade() {
        for (engine in TranslationEngine.values()) {
            assertFalse(
                "offline must block remote upgrade for $engine",
                OfflineTranslationPolicy.canUseRemoteUpgrade(true, engine)
            )
        }
    }

    @Test
    fun remoteUpgradeRequiresRemoteEngineAndOnlineMode() {
        assertFalse(OfflineTranslationPolicy.canUseRemoteUpgrade(false, TranslationEngine.ML_KIT))
        assertTrue(OfflineTranslationPolicy.canUseRemoteUpgrade(false, TranslationEngine.GEMINI))
        assertTrue(OfflineTranslationPolicy.canUseRemoteUpgrade(false, TranslationEngine.LIBRE_TRANSLATE))
    }

    @Test
    fun geminiOnlyEntryPointRequiresOnlineGeminiWithKeyAndClosedCircuit() {
        assertFalse(OfflineTranslationPolicy.canCreateGeminiOnly(true, TranslationEngine.GEMINI, true, false))
        assertFalse(OfflineTranslationPolicy.canCreateGeminiOnly(false, TranslationEngine.ML_KIT, true, false))
        assertFalse(OfflineTranslationPolicy.canCreateGeminiOnly(false, TranslationEngine.GEMINI, false, false))
        assertFalse(OfflineTranslationPolicy.canCreateGeminiOnly(false, TranslationEngine.GEMINI, true, true))
        assertTrue(OfflineTranslationPolicy.canCreateGeminiOnly(false, TranslationEngine.GEMINI, true, false))
    }
}
