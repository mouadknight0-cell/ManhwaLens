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
        assertEquals(23, report.visibleAccepted)
        assertEquals(23, report.visibleExactMatches)
        assertEquals(1.0, report.meanVisibleTokenF1, 0.000001)
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
    fun rawAndVisibleScoresAreReportedSeparatelyForSourceSpecificRepairs() {
        var engineCalls = 0
        val report = TranslationQualityBenchmark.run(
            translateOne = { prepared ->
                engineCalls++
                val case = TranslationQualityBenchmark.CASES.first { Glossary.prepare(it.source) == prepared }
                when (case.source) {
                    "No." -> "رقم"
                    "Run!" -> "تشغيل!"
                    "KRAK" -> "krak"
                    else -> case.referenceArabic
                }
            }
        )

        val no = report.cases.first { it.source == "No." }
        assertTrue(no.accepted) // Arabic-script guard alone cannot detect this semantic error.
        assertFalse(no.exactMatch)
        assertEquals("رقم", no.output)
        assertTrue(no.visibleAccepted)
        assertTrue(no.visibleExactMatch)
        assertEquals("لا.", no.visibleOutput)

        val run = report.cases.first { it.source == "Run!" }
        assertTrue(run.accepted)
        assertFalse(run.exactMatch)
        assertTrue(run.visibleExactMatch)
        assertEquals("اهرب!", run.visibleOutput)

        val krak = report.cases.first { it.source == "KRAK" }
        assertFalse(krak.accepted)
        assertTrue(krak.visibleAccepted)
        assertTrue(krak.visibleExactMatch)
        assertEquals("طَق!", krak.visibleOutput)

        assertEquals(23, report.visibleAccepted)
        assertEquals("Post-processing must reuse each raw candidate, not call ML Kit twice", 23, engineCalls)
        assertTrue(report.sourceOverridesApplied >= 3)
        assertTrue(report.toText().contains("RAW ENGINE:"))
        assertTrue(report.toText().contains("VISIBLE PIPELINE:"))
    }

    @Test
    fun visiblePipelineStillRejectsUncorrectedEnglishEchoes() {
        val report = TranslationQualityBenchmark.run(
            translateOne = { prepared ->
                val case = TranslationQualityBenchmark.CASES.first { Glossary.prepare(it.source) == prepared }
                if (case.source == "What did you just say?") case.source else case.referenceArabic
            }
        )

        val broken = report.cases.first { it.source == "What did you just say?" }
        assertFalse(broken.accepted)
        assertFalse(broken.visibleAccepted)
        assertEquals(ArabicValidator.Issue.UNTRANSLATED_ECHO, broken.visibleIssue)
        assertEquals("", broken.visibleOutput)
    }

    @Test
    fun visiblePipelineUsesTheSharedSanitizerBeforeScoring() {
        val report = TranslationQualityBenchmark.run(
            translateOne = { prepared ->
                val case = TranslationQualityBenchmark.CASES.first { Glossary.prepare(it.source) == prepared }
                if (case.id == 13) "الترجمة: ماذا قلت للتو؟" else case.referenceArabic
            }
        )

        val result = report.cases.first { it.id == 13 }
        assertTrue(result.visibleAccepted)
        assertEquals("ماذا قلت للتو؟", result.visibleOutput)
    }

    @Test
    fun missingTranslationIsNotTreatedAsSuccessful() {
        val report = TranslationQualityBenchmark.run(translateOne = { throw IllegalStateException("offline") })
        assertEquals(23, report.rejected)
        assertEquals(0, report.accepted)
    }
}
