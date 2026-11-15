package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationQualityBenchmarkTest {
    @Test
    fun referenceCorpus_hasStableIdsAndCategories() {
        assertEquals(23, TranslationQualityBenchmark.CASES.size)
        assertEquals((1..23).toList(), TranslationQualityBenchmark.CASES.map { it.id })
        assertTrue(TranslationQualityBenchmark.CASES.map { it.category }.toSet().containsAll(
            setOf("dialogue", "narration", "short", "names", "sfx", "idiom", "terminology")
        ))
    }

    @Test
    fun exactReferenceOutput_isAcceptedAndScoresOne() {
        val report = TranslationQualityBenchmark.run(
            translateOne = { source ->
                TranslationQualityBenchmark.CASES.first { Glossary.prepare(it.source) == source }.referenceArabic
            }
        )
        assertEquals(23, report.total)
        assertEquals(23, report.completed)
        assertEquals(23, report.accepted)
        assertEquals(23, report.exactMatches)
        assertEquals(1.0, report.meanTokenF1, 0.000001)
    }

    @Test
    fun englishEchoAndBlankAreCountedAsRejected() {
        val report = TranslationQualityBenchmark.run(
            translateOne = { source ->
                if (source.contains("ALRIGHT")) "ALRIGHT, I'LL HAVE A GLASS."
                else ""
            }
        )
        assertEquals(0, report.accepted)
        assertEquals(23, report.rejected)
        assertEquals(0.0, report.meanTokenF1, 0.0)
    }

    @Test
    fun alternateWordOrderGetsLexicalScoreWithoutClaimingSemanticAccuracy() {
        val report = TranslationQualityBenchmark.run(
            translateOne = { source ->
                val case = TranslationQualityBenchmark.CASES.first { Glossary.prepare(it.source) == source }
                case.referenceArabic.split(" ").reversed().joinToString(" ")
            }
        )
        assertTrue(report.meanTokenF1 > 0.99)
        assertFalse(report.toText().contains("semantic accuracy: 100"))
        assertTrue(report.toText().contains("NOT semantic accuracy"))
    }

    @Test
    fun missingTranslationIsNotTreatedAsSuccessful() {
        val report = TranslationQualityBenchmark.run(translateOne = { throw IllegalStateException("offline") })
        assertEquals(23, report.rejected)
        assertEquals(0, report.accepted)
    }
}
