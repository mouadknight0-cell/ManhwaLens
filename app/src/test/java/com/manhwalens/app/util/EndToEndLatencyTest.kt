package com.manhwalens.app.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v0.13.0 — the T0→T7 latency recorder: run lifecycle, first/full latency math,
 * median/P95/worst percentiles, stage medians, failed-run accounting.
 */
class EndToEndLatencyTest {

    private var now = 0L

    @Before
    fun setUp() {
        now = 0L
        EndToEndLatency.resetAll()
        EndToEndLatency.clock = { now }
    }

    @After
    fun tearDown() {
        EndToEndLatency.resetAll()
        EndToEndLatency.clock = { System.nanoTime() / 1_000_000L }
    }

    private fun advance(ms: Long) {
        now += ms
    }

    private fun fullRun(firstSentenceAt: Long, fullAt: Long) {
        EndToEndLatency.beginRun() // T0 at current `now`
        advance(firstSentenceAt)
        EndToEndLatency.mark(EndToEndLatency.Event.T6_FIRST_SENTENCE_DISPLAYED)
        advance(fullAt - firstSentenceAt)
        EndToEndLatency.mark(EndToEndLatency.Event.T7_ALL_DISPLAYED)
        EndToEndLatency.finishRun()
    }

    @Test
    fun firstAndFullLatencyAreT6minusT0AndT7minusT0() {
        fullRun(firstSentenceAt = 1200, fullAt = 3400)
        val run = EndToEndLatency.completedRuns().single()
        assertEquals(1200L, run.firstSentenceMs())
        assertEquals(3400L, run.fullMs())
    }

    @Test
    fun medianP95AndWorstAcrossRuns() {
        // First-sentence latencies: 100, 200, 300, 400, 500 -> median 300, p95 500, worst 500.
        for (v in listOf(100L, 200L, 300L, 400L, 500L)) fullRun(v, v + 1000)
        val s = EndToEndLatency.stats()
        assertEquals(5, s.completedRuns)
        assertEquals(300L, s.firstSentenceMedianMs)
        assertEquals(500L, s.firstSentenceP95Ms)
        assertEquals(500L, s.firstSentenceWorstMs)
        assertEquals(1300L, s.fullMedianMs)
        assertEquals(1500L, s.fullP95Ms)
        assertEquals(1500L, s.fullWorstMs)
    }

    @Test
    fun stageMediansCoverEveryMilestone() {
        EndToEndLatency.beginRun() // T0 at 0
        advance(100); EndToEndLatency.mark(EndToEndLatency.Event.T1_CAPTURE_COMPLETE)
        advance(50); EndToEndLatency.mark(EndToEndLatency.Event.T2_PREPROCESS_COMPLETE)
        advance(800); EndToEndLatency.mark(EndToEndLatency.Event.T3_OCR_COMPLETE)
        advance(120); EndToEndLatency.mark(EndToEndLatency.Event.T4_GROUPING_COMPLETE)
        advance(30); EndToEndLatency.mark(EndToEndLatency.Event.T5_TRANSLATION_STARTED)
        advance(900); EndToEndLatency.mark(EndToEndLatency.Event.T6_FIRST_SENTENCE_DISPLAYED)
        advance(2000); EndToEndLatency.mark(EndToEndLatency.Event.T7_ALL_DISPLAYED)
        EndToEndLatency.finishRun()

        val s = EndToEndLatency.stats()
        assertEquals(100L, s.stageMedianMs[EndToEndLatency.Event.T1_CAPTURE_COMPLETE])
        assertEquals(50L, s.stageMedianMs[EndToEndLatency.Event.T2_PREPROCESS_COMPLETE])
        assertEquals(800L, s.stageMedianMs[EndToEndLatency.Event.T3_OCR_COMPLETE])
        assertEquals(120L, s.stageMedianMs[EndToEndLatency.Event.T4_GROUPING_COMPLETE])
        assertEquals(30L, s.stageMedianMs[EndToEndLatency.Event.T5_TRANSLATION_STARTED])
        assertEquals(900L, s.stageMedianMs[EndToEndLatency.Event.T6_FIRST_SENTENCE_DISPLAYED])
        assertEquals(2000L, s.stageMedianMs[EndToEndLatency.Event.T7_ALL_DISPLAYED])
        assertEquals(2000L, s.firstSentenceMedianMs)
        assertEquals(4000L, s.fullMedianMs)
    }

    @Test
    fun firstMarkWins() {
        EndToEndLatency.beginRun()
        advance(100)
        EndToEndLatency.mark(EndToEndLatency.Event.T6_FIRST_SENTENCE_DISPLAYED)
        advance(5000)
        EndToEndLatency.mark(EndToEndLatency.Event.T6_FIRST_SENTENCE_DISPLAYED) // must be ignored
        EndToEndLatency.finishRun()
        val run = EndToEndLatency.completedRuns().single()
        assertEquals(100L, run.firstSentenceMs())
    }

    @Test
    fun marksWithoutAnActiveRunAreIgnored() {
        EndToEndLatency.mark(EndToEndLatency.Event.T6_FIRST_SENTENCE_DISPLAYED)
        assertNull(EndToEndLatency.finishRun())
        assertTrue(EndToEndLatency.completedRuns().isEmpty())
        assertEquals(0, EndToEndLatency.stats().failedRuns)
    }

    @Test
    fun runThatNeverDisplaysCountsAsFailedAndStaysOutOfPercentiles() {
        EndToEndLatency.beginRun()
        advance(500)
        EndToEndLatency.mark(EndToEndLatency.Event.T3_OCR_COMPLETE)
        EndToEndLatency.finishRun()
        val s = EndToEndLatency.stats()
        assertEquals(0, s.completedRuns)
        assertEquals(1, s.failedRuns)
        assertNull(s.firstSentenceMedianMs)
        assertNull(s.fullMedianMs)
    }

    @Test
    fun abandonedRunCountsAsFailedWhenTheNextRunBegins() {
        EndToEndLatency.beginRun()
        advance(100)
        EndToEndLatency.beginRun() // abandons the first run
        advance(200)
        EndToEndLatency.mark(EndToEndLatency.Event.T6_FIRST_SENTENCE_DISPLAYED)
        EndToEndLatency.finishRun()
        val s = EndToEndLatency.stats()
        assertEquals(1, s.completedRuns)
        assertEquals(1, s.failedRuns)
    }

    @Test
    fun dialogueStatsAndFailureRate() {
        EndToEndLatency.beginRun()
        EndToEndLatency.noteDialogueStats(regions = 12, translated = 10, failed = false)
        advance(100)
        EndToEndLatency.mark(EndToEndLatency.Event.T6_FIRST_SENTENCE_DISPLAYED)
        EndToEndLatency.finishRun()

        EndToEndLatency.beginRun()
        EndToEndLatency.noteDialogueStats(regions = 8, translated = 0, failed = true)
        advance(100)
        EndToEndLatency.mark(EndToEndLatency.Event.T6_FIRST_SENTENCE_DISPLAYED)
        EndToEndLatency.finishRun()

        val s = EndToEndLatency.stats()
        assertEquals(20L, s.totalDialogueRegions)
        assertEquals(10L, s.totalTranslatedSentences)
        assertNotNull(s.translationFailureRate)
        assertEquals(0.5, s.translationFailureRate!!, 0.0001)
    }

    @Test
    fun reportIsKeyFreeAndMentionsBothLatencies() {
        fullRun(900, 2500)
        val report = EndToEndLatency.report()
        assertTrue(report, report.contains("first sentence"))
        assertTrue(report, report.contains("full translation"))
        assertTrue(report, report.contains("900 ms"))
        assertTrue(report, report.contains("2500 ms"))
    }

    @Test
    fun ringIsBounded() {
        repeat(150) { fullRun(100, 200) }
        assertEquals(100, EndToEndLatency.completedRuns().size)
    }
}
