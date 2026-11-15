package com.manhwalens.app.translate

import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class GlossaryRefreshTest {

    @Test
    fun unchangedSettingsKeepTheCompiledGlossaryConfig() {
        val previous = Glossary.config
        try {
            Glossary.refresh("Wei Lin = وي لين", true)
            val configured = Glossary.config

            Glossary.refresh("Wei Lin = وي لين", true)

            assertSame(configured, Glossary.config)
        } finally {
            Glossary.refresh(previous.userText, previous.mergeLines)
        }
    }

    @Test
    fun changedSettingsReplaceTheGlossaryConfig() {
        val previous = Glossary.config
        try {
            Glossary.refresh("Wei Lin = وي لين", true)
            val configured = Glossary.config

            Glossary.refresh("Wei Lin = وي لين\nHan Ji-woo = هان جي وو", true)

            assertNotSame(configured, Glossary.config)
        } finally {
            Glossary.refresh(previous.userText, previous.mergeLines)
        }
    }
}
