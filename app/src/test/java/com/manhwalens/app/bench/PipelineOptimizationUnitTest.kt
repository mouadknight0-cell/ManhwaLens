package com.manhwalens.app.bench

import com.manhwalens.app.live.LiveLoopDriver
import com.manhwalens.app.live.LiveStabilityTracker
import com.manhwalens.app.ocr.BlockFilter
import com.manhwalens.app.ocr.BubbleBlock
import com.manhwalens.app.ocr.BubbleGrouper
import com.manhwalens.app.ocr.DetectedLine
import com.manhwalens.app.ocr.geometry.Rect2D
import com.manhwalens.app.render.AutoFitTextSizer
import com.manhwalens.app.render.ColorSampler
import com.manhwalens.app.render.FakeMonospaceMeasurer
import com.manhwalens.app.render.PatchMath
import com.manhwalens.app.translate.BubbleTextRules
import com.manhwalens.app.translate.Glossary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression & correctness tests covering the latency/CPU optimizations across:
 *  - EndToEndRecorder & EndToEndMetricsStore
 *  - BubbleGrouper visual row-band reading order
 *  - BlockFilter, BubbleTextRules, Glossary anchor index + prepareCache
 *  - PatchMath histogram sampleRing, ping-pong dilateMask, dirty-bbox inpaintMasked
 *  - ColorSampler ThreadLocal histogram
 *  - AutoFitTextSizer binary search parity
 *  - LiveLoopDriver speculative OCR callbacks (onSettlingClean / onCancelSpeculative)
 */
class PipelineOptimizationUnitTest {

    @Test
    fun bubbleGrouper_rowBandReadingOrder_sortsLeftToRightWithinSameVisualRow() {
        // Right bubble sits 4 px higher than left bubble, well within 50% line-height band.
        val rightSlightlyHigher = DetectedLine("SECOND", Rect2D(420f, 100f, 600f, 148f), 0.95f)
        val leftSlightlyLower = DetectedLine("FIRST", Rect2D(60f, 104f, 240f, 152f), 0.95f)
        val bottomBubble = DetectedLine("THIRD", Rect2D(180f, 320f, 360f, 368f), 0.95f)

        val blocks = BubbleGrouper.group(listOf(rightSlightlyHigher, leftSlightlyLower, bottomBubble))
        assertEquals(3, blocks.size)
        assertEquals("FIRST", blocks[0].text)
        assertEquals("SECOND", blocks[1].text)
        assertEquals("THIRD", blocks[2].text)
    }

    @Test
    fun glossary_anchorIndexAndMemoization_matchesExpectedSubstitutions() {
        Glossary.refresh("Shadow Sect = طائفة الظل\nWei Wuxian = وي ووشيان", mergeLines = true)
        val first = Glossary.prepare("Wei Wuxian entered the Shadow Sect!")
        val second = Glossary.prepare("Wei Wuxian entered the Shadow Sect!")
        assertEquals(first, second)
        assertTrue(first.contains("وي ووشيان"))
        assertTrue(first.contains("طائفة الظل"))

        val plain = Glossary.prepare("Hello there, wait a second.")
        assertFalse(plain.contains("وي ووشيان"))
    }

    @Test
    fun blockFilter_and_bubbleTextRules_preserveDialogueAndDropNoise() {
        val dialogue = BubbleBlock(1, listOf(DetectedLine("HELLO WORLD!", Rect2D(100f, 400f, 320f, 450f), 0.9f)))
        val sfx = BubbleBlock(2, listOf(DetectedLine("BOOM!", Rect2D(100f, 500f, 320f, 550f), 0.9f)))
        val watermark = BubbleBlock(3, listOf(DetectedLine("Read at mangadex.org", Rect2D(100f, 600f, 420f, 650f), 0.9f)))

        val res = BlockFilter.apply(
            blocks = listOf(dialogue, sfx, watermark),
            frameHeight = 1440,
            topPct = 5f,
            bottomPct = 5f,
            userIgnore = listOf("custom ignore")
        )
        assertEquals(1, res.kept.size)
        assertEquals("HELLO WORLD!", res.kept[0].text)
        assertEquals(2, res.removed.size)
        assertTrue(BubbleTextRules.oneLine("Line1\nLine2  ").contains("Line1 Line2"))
    }

    @Test
    fun patchMath_histogramSampleRing_and_dirtyBboxInpaint_preserveAccuracy() {
        val w = 32
        val h = 32
        val bg = 0xFFF0E8D8.toInt()
        val fg = 0xFF101010.toInt()
        val px = IntArray(w * h) { bg }
        // Draw dark strokes inside [10..22, 10..22]
        for (y in 12..19) {
            for (x in 12..19) {
                px[y * w + x] = fg
            }
        }
        val ring = PatchMath.sampleRing(px, w, h, Rect2D(10f, 10f, 22f, 22f))
        assertNotNull(ring)
        assertEquals(bg, ring!!.color)
        assertTrue(ring.uniform)

        val mask = PatchMath.buildTextMask(px, w, h, bg)
        val dilated = PatchMath.dilateMask(mask, w, h, 1)
        // Corner far outside dilated mask must remain false
        assertFalse(dilated[0])

        val iters = PatchMath.inpaintMasked(px, w, h, dilated, maxIterations = 64)
        assertTrue(iters > 0)
        assertEquals(0, PatchMath.countSuspectPixels(px, w, h, Rect2D(10f, 10f, 22f, 22f), bg))
        // Pixel outside dirty region must be untouched
        assertEquals(bg, px[0])
    }

    @Test
    fun colorSampler_and_autoFitTextSizer_produceExactResults() {
        val w = 40
        val h = 40
        val bg = 0xFF223344.toInt()
        val px = IntArray(w * h) { bg }
        // Minority stroke pixels
        for (x in 10 until 30) px[20 * w + x] = 0xFFFFFFFF.toInt()
        val sampled = ColorSampler.sample(px, w, h, 0, 0, w, h)
        assertEquals(bg, sampled)

        val measurer = FakeMonospaceMeasurer()
        val fit = AutoFitTextSizer.fit(
            text = "مرحبا بكم في عالم المانهوا الجميل",
            maxWidthPx = 220,
            maxHeightPx = 120,
            minPx = 8f,
            maxPx = 48f,
            measurer = measurer
        )
        assertTrue(fit.fits)
        assertTrue(fit.fontSizePx in 8f..48f)
        // Confirm size + 1f would overflow (or hit maxPx)
        if (fit.fontSizePx < 48f) {
            val next = measurer.measure("مرحبا بكم في عالم المانهوا الجميل", 220, fit.fontSizePx + 1f)
            assertTrue(next.heightPx > 120)
        }
    }

    @Test
    fun endToEndRecorder_and_metricsStore_computePercentilesAndSummary() {
        EndToEndMetricsStore.clear()
        var clock = 1_000L
        val rec = EndToEndRecorder(
            label = "unit",
            mode = "tap",
            clockMs = { clock },
            cpuTimeMsProvider = { clock },
            memoryUsedKbProvider = { 4096L }
        )
        clock += 5; rec.markT1CaptureDone()
        clock += 2; rec.markT2PreprocessDone()
        clock += 20; rec.markT3OcrDone()
        clock += 3; rec.markT4GroupingDone(4)
        clock += 1; rec.markT5TranslationStart()
        clock += 15; rec.markT6FirstSentenceDisplayed()
        clock += 30
        val run = rec.markT7AllDisplayed(translatedCount = 4, failedCount = 0, failed = false)
        EndToEndMetricsStore.record(run)

        assertEquals(46L, run.firstSentenceLatencyMs)
        assertEquals(76L, run.fullTranslationLatencyMs)
        assertEquals(76L, run.cpuTimeMs)
        assertEquals(4096L, run.memoryUsedKb)
        assertTrue(run.summaryLine().contains("T6-T0=46ms"))
        assertTrue(run.summaryLine().contains("T7-T0=76ms"))

        val sum = EndToEndMetricsStore.summarize()
        assertEquals(1, sum.runs)
        assertEquals(46L, sum.medianFirstSentenceMs)
        assertEquals(76L, sum.medianFullLatencyMs)
        EndToEndMetricsStore.clear()
    }

    @Test
    fun liveLoopDriver_triggersSpeculativeOcrOnCleanSettling_andCancelsOnScroll() {
        var now = 1_000L
        var settlingHits = 0
        var cancels = 0
        val pageA = ByteArray(64 * 128) { 10.toByte() }
        val pageB = ByteArray(64 * 128) { 90.toByte() }
        val pageC = ByteArray(64 * 128) { 170.toByte() }
        val queue = ArrayDeque(listOf(pageA, pageB, pageC))

        val driver = LiveLoopDriver(
            source = object : LiveLoopDriver.FrameSource<ByteArray> {
                override fun acquireNewFrame(): ByteArray? = if (queue.isEmpty()) null else queue.removeFirst()
                override fun grayOf(frame: ByteArray): ByteArray = frame
                override fun diffMask(): BooleanArray? = null
                override fun hasPatchesOnScreen(): Boolean = false
                override fun patchesBurnedInNow(): Boolean = false
                override fun patchCounts() = LiveLoopDriver.PatchCounts(0, 0)
                override fun releaseFrame(frame: ByteArray) {}
                override fun onShiftPatches(frame: ByteArray, dyThumbnailRows: Int, confidence: Float) = false
                override fun hideOverlay() = true
                override fun awaitNewFrame(timeoutMs: Long): ByteArray? = null
                override fun showOverlay() {}
            },
            sink = object : LiveLoopDriver.Sink<ByteArray> {
                override fun onStatus(status: LiveLoopDriver.DriverStatus) {}
                override fun onTranslate(frame: ByteArray, gray: ByteArray, frameOrigin: String) {}
                override fun onClearPatches(reason: String) {}
                override fun onPatchesMovedOrCleared() {}
                override fun onNoFramesYet(elapsedMs: Long) {}
                override fun onStarved(msSinceLastFrame: Long, verdict: LiveStabilityTracker.Verdict) {}
                override fun onCleanCaptureTimeout(consecutiveAttempts: Int) {}
                override fun onDiagnostics(diagnostics: LiveLoopDriver.Diagnostics) {}
                override fun trace(stage: String, detail: String) {}
                override fun onError(what: String) {}
                override fun onSettlingClean(frame: ByteArray, gray: ByteArray) { settlingHits++ }
                override fun onCancelSpeculative() { cancels++ }
            },
            clock = { now },
            stableWindowMs = 400L,
            changeThreshold = 10f
        )
        driver.startSession(now)

        // First frame -> Settling clean triggers speculative OCR
        driver.tick()
        assertEquals(1, settlingHits)
        // New scrolling frame -> Changing cancels speculative OCR
        now += 150L
        driver.tick()
        assertTrue(cancels >= 1)
        // Next still tick -> Settling clean triggers again
        now += 150L
        driver.tick()
        assertTrue(settlingHits >= 2)
    }
}
