package com.manhwalens.app.bench

import com.manhwalens.app.ocr.BlockFilter
import com.manhwalens.app.ocr.BubbleGrouper
import com.manhwalens.app.ocr.DetectedLine
import com.manhwalens.app.ocr.OcrGarbageClassifier
import com.manhwalens.app.ocr.geometry.Rect2D
import com.manhwalens.app.render.AutoFitTextSizer
import com.manhwalens.app.render.ColorSampler
import com.manhwalens.app.render.Measured
import com.manhwalens.app.render.PatchMath
import com.manhwalens.app.render.TextMeasurer
import com.manhwalens.app.translate.ArabicValidator
import com.manhwalens.app.translate.BlockPriority
import com.manhwalens.app.translate.BubbleTextRules
import com.manhwalens.app.translate.CachingTranslator
import com.manhwalens.app.translate.Glossary
import com.manhwalens.app.translate.PageDedupe
import com.manhwalens.app.translate.PageTranslation
import com.manhwalens.app.translate.TranslatedBlock
import com.manhwalens.app.translate.TranslationBlock
import com.manhwalens.app.translate.TranslationCache
import com.manhwalens.app.translate.Translator
import com.manhwalens.app.util.FrameDarkness
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

/**
 * Deterministic, pure-JVM End-to-End Pipeline Benchmark Suite (T0..T7).
 *
 * Exercises the integrated ManhwaLens pipeline across a fixed 10-screen dataset:
 *  Screen capture/pixel buffer -> Darkness/Preprocessing -> OCR lines -> BubbleGrouper ->
 *  BlockFilter + OcrGarbageClassifier -> PageDedupe + BlockPriority -> Glossary.prepare ->
 *  CachingTranslator (with progressive emission) -> ArabicValidator + polishArabic ->
 *  ColorSampler + PatchMath (ring/inpaint/mask) + AutoFitTextSizer -> Display callback (T6, T7).
 *
 * Used both in unit tests (to verify optimizations against baseline) and from Settings diagnostics.
 */
object EndToEndBenchmarkRunner {

    data class BenchmarkScreen(
        val id: String,
        val frameWidth: Int,
        val frameHeight: Int,
        val bgColor: Int,
        val textColor: Int,
        val lines: List<DetectedLine>,
        val expectedDialogueCount: Int,
        val simulatedCaptureMs: Long = 8L,
        val simulatedOcrMs: Long = 20L
    )

    data class BenchmarkConfig(
        val rounds: Int = 3,
        val simulatedPerBlockTranslateMs: Long = 18L,
        val simulatedBatchTranslateMs: Long = 14L,
        val simulateSleep: Boolean = true,
        val progressiveDisplay: Boolean = true,
        val clearCacheEachRound: Boolean = true
    )

    data class BenchmarkResult(
        val summary: EndToEndSummary,
        val records: List<EndToEndRunRecord>,
        val displayedTextsByScreen: Map<String, List<String>>
    )

    /** Fixed 10-screen dataset covering all real manhwa reading & rendering scenarios. */
    val FIXED_DATASET: List<BenchmarkScreen> by lazy { buildFixedDataset() }

    private fun buildFixedDataset(): List<BenchmarkScreen> = listOf(
        // 1. Dense multi-bubble dialogue with pinyin names & multi-line wrapping
        BenchmarkScreen(
            id = "01_dense_dialogue",
            frameWidth = 720,
            frameHeight = 1440,
            bgColor = 0xFFFFFFFF.toInt(),
            textColor = 0xFF1A1A1A.toInt(),
            expectedDialogueCount = 5,
            lines = listOf(
                DetectedLine("WEI WUXIAN!", 80f, 120f, 310f, 162f),
                DetectedLine("WAIT FOR ME!", 95f, 168f, 295f, 210f),
                DetectedLine("DON'T GO INSIDE", 410f, 220f, 650f, 260f),
                DetectedLine("THE DEMON CAVE", 405f, 266f, 655f, 306f),
                DetectedLine("ALONE!", 470f, 312f, 590f, 352f),
                DetectedLine("IT'S TOO", 100f, 540f, 260f, 580f),
                DetectedLine("DANGEROUS!", 90f, 586f, 270f, 626f),
                DetectedLine("I KNOW WHAT", 380f, 780f, 610f, 820f),
                DetectedLine("I'M DOING, LAN", 370f, 826f, 620f, 866f),
                DetectedLine("WANGJI.", 430f, 872f, 560f, 912f),
                DetectedLine("HURRY!", 140f, 1080f, 270f, 1122f)
            )
        ),
        // 2. Action scene with giant SFX + short urgent callout + explanation
        BenchmarkScreen(
            id = "02_action_sfx",
            frameWidth = 720,
            frameHeight = 1440,
            bgColor = 0xFFF4F1EA.toInt(),
            textColor = 0xFF141414.toInt(),
            expectedDialogueCount = 2,
            lines = listOf(
                DetectedLine("KRAKOOOM", 30f, 100f, 680f, 250f),
                DetectedLine("LOOK OUT!", 240f, 440f, 440f, 484f),
                DetectedLine("THE BARRIER IS", 140f, 820f, 480f, 862f),
                DetectedLine("BREAKING APART!", 135f, 868f, 485f, 910f)
            )
        ),
        // 3. Dark-themed panel (white text on dark bubble)
        BenchmarkScreen(
            id = "03_dark_panel",
            frameWidth = 720,
            frameHeight = 1440,
            bgColor = 0xFF181822.toInt(),
            textColor = 0xFFF2F2F8.toInt(),
            expectedDialogueCount = 3,
            lines = listOf(
                DetectedLine("YOU NEVER", 110f, 220f, 330f, 262f),
                DetectedLine("ESCAPED.", 120f, 268f, 320f, 310f),
                DetectedLine("WHO IS", 420f, 560f, 580f, 602f),
                DetectedLine("THERE?!", 415f, 608f, 585f, 650f),
                DetectedLine("STEP INTO THE LIGHT.", 140f, 980f, 540f, 1024f)
            )
        ),
        // 4. Staggered/split same-row lines + hyphenated wrapped words
        BenchmarkScreen(
            id = "04_staggered_hyphen",
            frameWidth = 720,
            frameHeight = 1440,
            bgColor = 0xFFFFFFFF.toInt(),
            textColor = 0xFF111111.toInt(),
            expectedDialogueCount = 2,
            lines = listOf(
                DetectedLine("WE MUST", 140f, 204f, 290f, 246f),
                DetectedLine("CON-", 305f, 200f, 395f, 242f),
                DetectedLine("TINUE NOW!", 175f, 252f, 360f, 294f),
                DetectedLine("OTHERWISE", 350f, 680f, 580f, 722f),
                DetectedLine("EVERYTHING", 340f, 728f, 590f, 770f),
                DetectedLine("WILL COLLAPSE.", 320f, 776f, 610f, 818f)
            )
        ),
        // 5. Long monologue (>400 chars requiring TextChunker sentence splitting)
        BenchmarkScreen(
            id = "05_long_monologue",
            frameWidth = 720,
            frameHeight = 1440,
            bgColor = 0xFFFAF8F2.toInt(),
            textColor = 0xFF1E1E1E.toInt(),
            expectedDialogueCount = 1,
            lines = listOf(
                DetectedLine("Ten years ago the northern clan fell during the winter siege.", 70f, 240f, 650f, 282f),
                DetectedLine("Nobody believed a single survivor could cross the frozen river.", 70f, 288f, 650f, 330f),
                DetectedLine("Yet every night the watchtowers still saw a blue lantern.", 70f, 336f, 650f, 378f),
                DetectedLine("Now the elders have summoned every disciple back to the gate.", 70f, 384f, 650f, 426f),
                DetectedLine("Prepare your blades before midnight arrives.", 70f, 432f, 650f, 474f),
                DetectedLine("Because tonight the mountain seal will finally open again.", 70f, 480f, 650f, 522f),
                DetectedLine("Do not hesitate when the signal bell rings.", 70f, 528f, 650f, 570f),
                DetectedLine("Hold the inner courtyard until dawn breaks.", 70f, 576f, 650f, 618f)
            )
        ),
        // 6. Repeated identical bubbles (verifies PageDedupe + cache)
        BenchmarkScreen(
            id = "06_repeated_dialogue",
            frameWidth = 720,
            frameHeight = 1440,
            bgColor = 0xFFFFFFFF.toInt(),
            textColor = 0xFF181818.toInt(),
            expectedDialogueCount = 4,
            lines = listOf(
                DetectedLine("WAIT!", 120f, 180f, 250f, 222f),
                DetectedLine("WAIT!", 450f, 400f, 580f, 442f),
                DetectedLine("STOP RIGHT THERE!", 140f, 700f, 480f, 742f),
                DetectedLine("WAIT!", 310f, 1020f, 440f, 1062f)
            )
        ),
        // 7. Browser chrome + watermark + edge-band UI + real dialogue
        BenchmarkScreen(
            id = "07_chrome_watermarks",
            frameWidth = 720,
            frameHeight = 1440,
            bgColor = 0xFFFFFFFF.toInt(),
            textColor = 0xFF151515.toInt(),
            expectedDialogueCount = 2,
            lines = listOf(
                DetectedLine("12:45", 20f, 12f, 100f, 42f),
                DetectedLine("manhwatop.com", 200f, 75f, 520f, 110f),
                DetectedLine("WHAT DID YOU SAY?", 150f, 420f, 510f, 464f),
                DetectedLine("I SAID WE ARE LEAVING.", 160f, 840f, 560f, 884f),
                DetectedLine("Asura Scans", 230f, 1370f, 490f, 1408f)
            )
        ),
        // 8. Empty / pure artwork screen (0 dialogue regions)
        BenchmarkScreen(
            id = "08_empty_panel",
            frameWidth = 720,
            frameHeight = 1440,
            bgColor = 0xFFEDE8DF.toInt(),
            textColor = 0xFF202020.toInt(),
            expectedDialogueCount = 0,
            lines = emptyList()
        ),
        // 9. Mixed proper names & sect terms (glossary + Arabic validator)
        BenchmarkScreen(
            id = "09_glossary_names",
            frameWidth = 720,
            frameHeight = 1440,
            bgColor = 0xFFFFFFFF.toInt(),
            textColor = 0xFF111111.toInt(),
            expectedDialogueCount = 3,
            lines = listOf(
                DetectedLine("Sect Leader Jiang", 110f, 220f, 430f, 262f),
                DetectedLine("sent a letter.", 135f, 268f, 405f, 310f),
                DetectedLine("Show it to Hua Cheng.", 210f, 640f, 590f, 684f),
                DetectedLine("Right away, Senior!", 180f, 1040f, 530f, 1084f)
            )
        ),
        // 10. Mixed short urgent line + long background box (progressive priority test)
        BenchmarkScreen(
            id = "10_progressive_priority",
            frameWidth = 720,
            frameHeight = 1440,
            bgColor = 0xFFFFFFFF.toInt(),
            textColor = 0xFF111111.toInt(),
            expectedDialogueCount = 4,
            lines = listOf(
                DetectedLine("THE ANCIENT DRAGON SEAL WAS FORGED THREE HUNDRED YEARS AGO", 60f, 160f, 660f, 202f),
                DetectedLine("TO PROTECT THE EASTERN KINGDOM FROM THE ABYSSAL TIDE.", 60f, 208f, 660f, 250f),
                DetectedLine("RUN!", 280f, 540f, 420f, 586f),
                DetectedLine("WATCH OUT FOR THE", 170f, 840f, 520f, 882f),
                DetectedLine("FALLING PILLARS!", 185f, 888f, 505f, 930f),
                DetectedLine("OKAY!", 300f, 1180f, 430f, 1222f)
            )
        )
    )

    private fun DetectedLine(text: String, l: Float, t: Float, r: Float, b: Float) =
        DetectedLine(text, Rect2D(l, t, r, b), 0.94f)

    fun run(config: BenchmarkConfig = BenchmarkConfig()): BenchmarkResult {
        Glossary.refresh("", mergeLines = true)
        val memCache = TranslationCache()
        val records = ArrayList<EndToEndRunRecord>(config.rounds * FIXED_DATASET.size)
        val displayedByScreen = LinkedHashMap<String, List<String>>()

        // Reuse one synthetic bitmap pixel array per color profile to avoid skewing memory benchmarks.
        val samplePatchCache = HashMap<Long, IntArray>()
        val measurer = PureJvmArabicMeasurer()

        val cpuProvider = buildCpuTimeProvider()
        val memProvider: () -> Long = {
            val rt = Runtime.getRuntime()
            (rt.totalMemory() - rt.freeMemory()) / 1024L
        }

        val patchPool = Executors.newFixedThreadPool(2) { r ->
            Thread(r, "bench-patch").apply { isDaemon = true }
        }
        try {
            repeat(config.rounds) { round ->
                if (config.clearCacheEachRound) memCache.clear()
                for (screen in FIXED_DATASET) {
                    val recorder = EndToEndRecorder(
                        label = "${screen.id}_r$round",
                        mode = "benchmark",
                        clockMs = { System.nanoTime() / 1_000_000L },
                        cpuTimeMsProvider = cpuProvider,
                        memoryUsedKbProvider = memProvider
                    )

                    // T0 -> T1: Screen scanning + simulated frame capture
                    if (config.simulateSleep && screen.simulatedCaptureMs > 0L) {
                        Thread.sleep(screen.simulatedCaptureMs)
                    }
                    val frameKey = (screen.bgColor.toLong() shl 32) or (screen.textColor.toLong() and 0xFFFFFFFFL)
                    val samplePx = samplePatchCache.getOrPut(frameKey) {
                        buildSyntheticBubbleBuffer(220, 120, screen.bgColor, screen.textColor)
                    }
                    recorder.markT1CaptureDone()

                    // T1 -> T2: Preprocessing (darkness check + frame bounds validation)
                    val darkSamples = IntArray(FrameDarkness.DENSE_GRID * FrameDarkness.DENSE_GRID) { screen.bgColor }
                    FrameDarkness.isProtectedBlack(darkSamples)
                    recorder.markT2PreprocessDone()

                    // T2 -> T3: OCR execution
                    if (config.simulateSleep && screen.simulatedOcrMs > 0L && screen.lines.isNotEmpty()) {
                        Thread.sleep(screen.simulatedOcrMs)
                    }
                    val rawLines = screen.lines
                    recorder.markT3OcrDone()

                    // T3 -> T4: Text grouping, filtering, garbage rejection, dedupe, priority & sentence reconstruction
                    val grouped = BubbleGrouper.group(rawLines)
                    val filtered = BlockFilter.apply(
                        blocks = grouped,
                        frameHeight = screen.frameHeight,
                        topPct = 5f,
                        bottomPct = 5f,
                        userIgnore = emptyList()
                    ).kept
                    val garbageClassifier = OcrGarbageClassifier(
                        ownedStrings = emptySet(),
                        nowMs = { System.nanoTime() / 1_000_000L }
                    )
                    val cleanBlocks = filtered.filter { block ->
                        garbageClassifier.classify(
                            text = block.text,
                            box = block.bounds,
                            patchRects = emptyList(),
                            ownedRects = emptyList()
                        ).keep
                    }

                    val preparedTexts = HashMap<Int, String>(cleanBlocks.size)
                    val orderedBlocks = if (cleanBlocks.isEmpty()) {
                        emptyList()
                    } else {
                        val orderIdx = BlockPriority.orderRects(
                            cleanBlocks.map { it.text },
                            cleanBlocks.map { it.bounds.left },
                            cleanBlocks.map { it.bounds.top },
                            cleanBlocks.map { it.bounds.right },
                            cleanBlocks.map { it.bounds.bottom },
                            screen.frameWidth,
                            screen.frameHeight
                        )
                        orderIdx.map { idx ->
                            val b = cleanBlocks[idx]
                            preparedTexts[b.id] = Glossary.prepare(b.text)
                            TranslationBlock(b.id, b.text)
                        }
                    }

                    val deduped = PageDedupe.plan(orderedBlocks)
                    recorder.markT4GroupingDone(orderedBlocks.size)

                    if (orderedBlocks.isEmpty()) {
                        recorder.markT5TranslationStart()
                        displayedByScreen[screen.id] = emptyList()
                        records += recorder.markT7AllDisplayed(translatedCount = 0, failedCount = 0, failed = false)
                        continue
                    }

                    // T4 -> T5: Prepare translation & progressive paint handler
                    val displayedLock = Any()
                    val displayed = LinkedHashMap<Int, String>(orderedBlocks.size)
                    val pendingRender = AtomicInteger(orderedBlocks.size)
                    val renderDoneLatch = CountDownLatch(1)
                    val blockById = cleanBlocks.associateBy { it.id }

                    fun renderAndDisplayBlock(id: Int, arabicRaw: String) {
                        val blk = blockById[id] ?: return
                        val polished = BubbleTextRules.polishArabic(arabicRaw)
                        val valid = ArabicValidator.issue(blk.text, polished) == null
                        if (!valid) {
                            if (pendingRender.decrementAndGet() == 0) renderDoneLatch.countDown()
                            return
                        }
                        val renderTask = Runnable {
                            val bg = ColorSampler.sample(samplePx, 220, 120, 10, 10, 210, 110) ?: screen.bgColor
                            val fg = ColorSampler.bestTextColorOn(bg)
                            PatchMath.unionWithPad(listOf(blk.bounds), 5f, screen.frameWidth, screen.frameHeight)
                            PatchMath.sampleRing(samplePx, 220, 120, Rect2D(8f, 8f, 212f, 112f))
                            val mask = PatchMath.buildTextMask(samplePx, 220, 120, bg)
                            val dilated = PatchMath.dilateMask(mask, 220, 120, 1)
                            PatchMath.inpaintMasked(samplePx.copyOf(), 220, 120, dilated, maxIterations = 8)
                            val fit = AutoFitTextSizer.fit(
                                text = polished,
                                maxWidthPx = blk.bounds.width.toInt().coerceAtLeast(80),
                                maxHeightPx = blk.bounds.height.toInt().coerceAtLeast(36),
                                measurer = measurer
                            )
                            check(fg != 0 && fit.fontSizePx > 0f)
                            synchronized(displayedLock) {
                                displayed[id] = polished
                                recorder.markT6FirstSentenceDisplayed()
                            }
                            if (pendingRender.decrementAndGet() == 0) renderDoneLatch.countDown()
                        }
                        if (config.progressiveDisplay) {
                            patchPool.execute(renderTask)
                        } else {
                            renderTask.run()
                        }
                    }

                    val engine = SimulatedManhwaTranslator(
                        perBlockMs = if (config.simulateSleep) config.simulatedPerBlockTranslateMs else 0L,
                        batchMs = if (config.simulateSleep) config.simulatedBatchTranslateMs else 0L,
                        onBlockReady = if (config.progressiveDisplay) { b ->
                            renderAndDisplayBlock(b.id, b.arabic)
                            val key = deduped.idToKey[b.id]
                            for (dupId in deduped.keyToIds[key].orEmpty()) {
                                if (dupId != b.id) renderAndDisplayBlock(dupId, b.arabic)
                            }
                        } else null
                    )

                    val caching = CachingTranslator(
                        delegate = engine,
                        cache = memCache,
                        onBlockReady = if (config.progressiveDisplay) { b ->
                            renderAndDisplayBlock(b.id, b.arabic)
                            val key = deduped.idToKey[b.id]
                            for (dupId in deduped.keyToIds[key].orEmpty()) {
                                if (dupId != b.id) renderAndDisplayBlock(dupId, b.arabic)
                            }
                        } else null
                    )

                    recorder.markT5TranslationStart()
                    val result = caching.translatePage(deduped.unique)

                    var failedCount = 0
                    var pageFailed = false
                    when (result) {
                        is PageTranslation.Success -> {
                            if (!config.progressiveDisplay) {
                                val expanded = PageDedupe.expand(deduped, orderedBlocks, result.blocks)
                                for (tb in expanded) {
                                    renderAndDisplayBlock(tb.id, tb.arabic)
                                }
                            } else {
                                // Ensure any blocks already returned synchronously finished rendering
                                val returnedIds = result.blocks.map { it.id }.toSet()
                                for (req in deduped.unique) {
                                    if (req.id !in returnedIds) {
                                        failedCount++
                                        if (pendingRender.decrementAndGet() == 0) renderDoneLatch.countDown()
                                    }
                                }
                            }
                            renderDoneLatch.await()
                            failedCount = (orderedBlocks.size - displayed.size).coerceAtLeast(0)
                        }
                        is PageTranslation.Failure -> {
                            pageFailed = true
                            failedCount = orderedBlocks.size
                        }
                    }

                    displayedByScreen[screen.id] = orderedBlocks.mapNotNull { displayed[it.id] }
                    records += recorder.markT7AllDisplayed(
                        translatedCount = displayed.size,
                        failedCount = failedCount,
                        failed = pageFailed
                    )
                }
            }
        } finally {
            patchPool.shutdownNow()
        }

        return BenchmarkResult(
            summary = EndToEndSummary.summarize(records),
            records = records,
            displayedTextsByScreen = displayedByScreen
        )
    }

    private fun buildSyntheticBubbleBuffer(w: Int, h: Int, bg: Int, fg: Int): IntArray {
        val px = IntArray(w * h) { bg }
        // Paint synthetic glyph bands inside the inner box so ColorSampler & buildTextMask do real work.
        for (y in 24 until h - 24 step 18) {
            for (dy in 0 until 6) {
                val row = (y + dy) * w
                for (x in 28 until w - 28 step 5) {
                    px[row + x] = fg
                    px[row + x + 1] = fg
                }
            }
        }
        return px
    }

    private class PureJvmArabicMeasurer : TextMeasurer {
        override fun measure(text: String, maxWidthPx: Int, fontSizePx: Float): Measured {
            if (text.isBlank()) return Measured(0, 0)
            val charW = (fontSizePx * 0.55f).coerceAtLeast(1f)
            val charsPerLine = (maxWidthPx / charW).toInt().coerceAtLeast(1)
            val words = text.split(' ')
            var lines = 1
            var col = 0
            for (w in words) {
                val len = w.length + if (col > 0) 1 else 0
                if (col > 0 && col + len > charsPerLine) {
                    lines++
                    col = w.length
                } else {
                    col += len
                }
            }
            val lineH = ceil(fontSizePx * 1.18f).toInt().coerceAtLeast(1)
            return Measured(
                heightPx = lines * lineH,
                maxLineWidthPx = maxWidthPx
            )
        }
    }

    /**
     * Deterministic simulated EN->AR translator matching ML Kit / remote engine timing patterns:
     * - In progressive mode (`onBlockReady != null`), emits each block as soon as its translation completes.
     * - In batch mode (`onBlockReady == null`), waits for all blocks before returning.
     */
    private class SimulatedManhwaTranslator(
        private val perBlockMs: Long,
        private val batchMs: Long,
        private val onBlockReady: ((TranslatedBlock) -> Unit)? = null
    ) : Translator {
        override val engineId = "sim_benchmark"

        override fun translatePage(blocks: List<TranslationBlock>): PageTranslation {
            val t0 = System.nanoTime()
            if (blocks.isEmpty()) {
                return PageTranslation.Success(emptyList(), engineId, 0L, 0, 0)
            }
            val out = ArrayList<TranslatedBlock>(blocks.size)
            val err = AtomicReference<Throwable?>(null)
            for (b in blocks) {
                val prepared = Glossary.prepare(b.english)
                if (perBlockMs > 0L) Thread.sleep(perBlockMs)
                val ar = translateDeterministic(prepared)
                val tb = TranslatedBlock(b.id, b.english, ar)
                out += tb
                val cb = onBlockReady
                if (cb != null) {
                    runCatching { cb.invoke(tb) }.onFailure { err.compareAndSet(null, it) }
                }
            }
            if (onBlockReady == null && batchMs > 0L) {
                Thread.sleep(batchMs)
            }
            err.get()?.let {
                return PageTranslation.Failure(
                    com.manhwalens.app.translate.FailureReason.UNKNOWN,
                    it.message ?: "error",
                    engineId
                )
            }
            val elapsed = (System.nanoTime() - t0) / 1_000_000L
            return PageTranslation.Success(out, engineId, elapsed, 0, blocks.size)
        }

        private fun translateDeterministic(preparedEnglish: String): String {
            // Preserve any Arabic names already injected by Glossary, and generate realistic Arabic dialogue.
            val words = preparedEnglish.split(' ').filter { it.isNotBlank() }
            val arabicTokens = words.map { w ->
                if (w.any { it in '\u0600'..'\u06FF' }) w else DICT[w.lowercase().trim('.', '!', '?', ',')] ?: "كلمة"
            }
            return arabicTokens.joinToString(" ").ifBlank { "ترجمة تجريبية" }
        }

        companion object {
            private val DICT = mapOf(
                "wait" to "انتظر",
                "for" to "من",
                "me" to "أجلي",
                "don't" to "لا",
                "go" to "تذهب",
                "inside" to "إلى الداخل",
                "the" to "ال",
                "demon" to "شيطاني",
                "cave" to "الكهف",
                "alone" to "وحدك",
                "it's" to "إنه",
                "too" to "جدًا",
                "dangerous" to "خطير",
                "i" to "أنا",
                "know" to "أعرف",
                "what" to "ماذا",
                "i'm" to "أفعل",
                "doing" to "أفعله",
                "hurry" to "أسرع",
                "look" to "انتبه",
                "out" to "جيدًا",
                "barrier" to "الحاجز",
                "is" to "يتحطم",
                "breaking" to "يتكسر",
                "apart" to "تمامًا",
                "you" to "أنت",
                "never" to "أبدًا",
                "escaped" to "تهرب",
                "who" to "من",
                "there" to "هناك",
                "step" to "اخطُ",
                "into" to "نحو",
                "light" to "الضوء",
                "we" to "نحن",
                "must" to "يجب أن",
                "continue" to "نواصل",
                "now" to "الآن",
                "otherwise" to "وإلا",
                "everything" to "كل شيء",
                "will" to "سوف",
                "collapse" to "ينهار",
                "run" to "اهرب",
                "okay" to "حسنًا",
                "watch" to "احذر",
                "falling" to "الساقطة",
                "pillars" to "الأعمدة",
                "sent" to "أرسل",
                "a" to "رسالة",
                "letter" to "مكتوبة",
                "show" to "أرِها",
                "it" to "إياها",
                "to" to "إلى",
                "right" to "حالًا",
                "away" to "فورًا",
                "senior" to "يا سيدي",
                "said" to "قلت",
                "did" to "فعلت",
                "say" to "تقول",
                "are" to "نحن",
                "leaving" to "نغادر"
            )
        }
    }

    private fun buildCpuTimeProvider(): () -> Long {
        val jvmBean: (() -> Long)? = try {
            val mgmt = Class.forName("java.lang.management.ManagementFactory")
            val bean = mgmt.getMethod("getThreadMXBean").invoke(null)
            val supported = bean.javaClass.getMethod("isCurrentThreadCpuTimeSupported").invoke(bean) as? Boolean
            if (supported == true) {
                val method = bean.javaClass.getMethod("getCurrentThreadCpuTime")
                val fn: () -> Long = { (method.invoke(bean) as Long) / 1_000_000L }
                fn
            } else {
                null
            }
        } catch (_: Throwable) {
            null
        }
        if (jvmBean != null) return jvmBean

        val androidDebug: (() -> Long)? = try {
            val dbg = Class.forName("android.os.Debug")
            val method = dbg.getMethod("threadCpuTimeNanos")
            val fn: () -> Long = { ((method.invoke(null) as Long).coerceAtLeast(0L)) / 1_000_000L }
            fn
        } catch (_: Throwable) {
            null
        }
        return androidDebug ?: { -1L }
    }
}
