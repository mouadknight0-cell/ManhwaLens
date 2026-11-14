package com.manhwalens.app.translate.mlkit

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.nl.translate.Translation
import com.manhwalens.app.translate.FailureReason
import com.manhwalens.app.translate.BatchAutoSelect
import com.manhwalens.app.translate.BatchPolicyHolder
import com.manhwalens.app.translate.BatchSplitter
import com.manhwalens.app.translate.Glossary
import com.manhwalens.app.translate.MlKitPageAssembly
import com.manhwalens.app.translate.MlKitWarmth
import com.manhwalens.app.translate.SceneNames
import com.manhwalens.app.translate.SplitStrategy
import com.manhwalens.app.translate.TextChunker
import com.manhwalens.app.translate.PageTranslation
import com.manhwalens.app.translate.Translator
import com.manhwalens.app.translate.TranslatedBlock
import com.manhwalens.app.translate.TranslationBlock
import com.manhwalens.app.util.PipelineTracer
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * On-device ML Kit EN→AR translation with a strictly-local fallback chain (v0.9.9):
 *  default (no listener — Live and the offline fallback):
 *  1) per-block translation, ≤ 2 in flight (benchmarked level) — the release default. Device evidence showed
 *     the old "@@@" joined call losing its separators, so every page paid a failed joined
 *     attempt PLUS the per-block pass;
 *  2) ONE joined request is used instead only when [BatchPolicy] holds a separator that a
 *     self-test/benchmark PROVED on this device. The reply is fully validated
 *     ([BatchSplitter.validate]); one mismatch disables joined mode for the session and for
 *     7 days and the page is redone in parallel.
 *  progressive mode (onBlockReady listener attached — the tap pipeline):
 *  per-block translation, ≤ 2 in flight (benchmarked level), in priority order; every block's result is pushed
 *  to the listener as it completes. Sequential per-block is the fallback for both modes.
 *
 * Blocks longer than 400 chars bypass both strategies: they are split into
 * sentence chunks (≤ ~380 chars), each chunk translated, and reassembled —
 * so a novel-length bubble never poisons the joined mapping nor stalls the page.
 *
 * The ML Kit client is a process-wide SINGLETON (shared with pre-warm and the benchmark) —
 * translators/OCR are never recreated per tap, and the service keeps it hot with a dummy
 * call at start and on screen-on ([MlKitWarmth]).
 *
 * The Arabic model (~30 MB) is downloaded once, on first use, over any
 * connection — after that, this engine works fully offline. Nothing ever
 * leaves the device.
 *
 * Call from a background thread.
 */
class MlKitTranslator(
    /** Progressive display hook: invoked once per finished block (non-blank arabic only). */
    private val onBlockReady: ((TranslatedBlock) -> Unit)? = null,
    /**
     * P1: the authoritative page table from the persistent series memory
     * ([com.manhwalens.app.data.NameSession]). When supplied it OUTRANKS the fresh page guess
     * from [SceneNames.pin], so a locked spelling can never be replaced by a new detection.
     * Null keeps the v0.11.0 behaviour (page-local names only).
     */
    private val nameTableProvider: ((List<String>) -> Map<String, String>)? = null
) : Translator {

    override val engineId = "ml_kit_en_ar"

    /**
     * v0.11.0: names seen on THIS page are pinned to one Arabic spelling before the engine
     * runs. ML Kit cannot follow a prompt, so the only lever is the source text itself.
     * The table is built once per page in [translatePage] and cleared afterwards, so a name
     * never leaks into the next page's translation as a stale substitution.
     */
    @Volatile
    private var pageNames: Map<String, String> = emptyMap()

    /** v0.9.14: one sentence per bubble + known names already in Arabic before the engine runs. */
    private fun src(text: String): String = SceneNames.apply(Glossary.prepare(text), pageNames)

    private val client get() = SHARED_CLIENT

    override fun translatePage(blocks: List<TranslationBlock>): PageTranslation {
        val start = System.currentTimeMillis()
        if (blocks.isEmpty()) {
            return PageTranslation.Failure(FailureReason.EMPTY_INPUT, "No blocks to translate", engineId)
        }

        // 1) Ensure the EN→AR model exists (first call needs internet). Bounded wait:
        //    an unbounded Tasks.await here was the ~26 s hidden behind "translation".
        if (!sharedModelReady) {
            val tModel = System.currentTimeMillis()
            try {
                Log.i(TAG, "en->ar model not present yet; requesting download (one-time, ~30 MB)")
                Tasks.await(client.downloadModelIfNeeded(), MODEL_TIMEOUT_SEC, TimeUnit.SECONDS)
                sharedModelReady = true
                val ms = System.currentTimeMillis() - tModel
                PipelineTracer.span("mlkit model download", ms, "EN→AR ~30 MB (one-time)")
                Log.i(TAG, "en->ar model ready in $ms ms")
            } catch (e: Exception) {
                val ms = System.currentTimeMillis() - tModel
                PipelineTracer.span("mlkit model download FAILED", ms, e.javaClass.simpleName)
                Log.e("ManhwaLens", "en->ar model download failed in $ms ms — ${e.javaClass.name}: ${e.message}", e)
                return PageTranslation.Failure(
                    FailureReason.MODEL_DOWNLOAD_FAILED,
                    e.message ?: "Failed to download EN->AR model",
                    engineId
                )
            }
        } else {
            PipelineTracer.span("mlkit model check", 0, "ready")
        }

        val translatable = blocks.filter { it.english.isNotBlank() }
        if (translatable.isEmpty()) {
            return PageTranslation.Success(
                blocks = blocks.map { TranslatedBlock(it.id, it.english, "") },
                engineId = engineId,
                elapsedMs = System.currentTimeMillis() - start,
                cacheHits = 0,
                cacheMisses = 0
            )
        }

        // 1b) Scene names for this page (v0.11.0): detect the proper nouns once and pin one
        // Arabic spelling for each, so "Han Ji-woo" is not spelled three ways in one page.
        pageNames = runCatching {
            val sources = translatable.map { it.english }
            val seriesTable = nameTableProvider?.invoke(sources).orEmpty()
            val pageTable = SceneNames.pin(sources)
            // Series memory first (locked/user/built-in spellings), page detections fill gaps.
            // Longest name first in BOTH cases, so "Wei Wuxian" is replaced before "Wei".
            val table: Map<String, String> = if (seriesTable.isEmpty()) {
                pageTable
            } else {
                val merged = LinkedHashMap<String, String>(seriesTable)
                for ((name, arabic) in pageTable) merged.putIfAbsent(name, arabic)
                LinkedHashMap<String, String>().apply {
                    for ((name, arabic) in merged.entries.sortedByDescending { it.key.length }) {
                        put(name, arabic)
                    }
                }
            }
            if (table.isNotEmpty()) {
                PipelineTracer.span(
                    "mlkit scene names", 0,
                    if (seriesTable.isEmpty()) SceneNames.describe(table)
                    else "series ${seriesTable.size} + page ${table.size - seriesTable.size} · ${SceneNames.describe(table)}"
                )
            }
            table
        }.getOrDefault(emptyMap())

        // 2) Split off >400-char blocks: sentence-chunk + per-block translation, reassemble.
        val longBlocks = translatable.filter { TextChunker.needsChunk(it.english) }
        val normal = translatable.filter { !TextChunker.needsChunk(it.english) }
        val answered = HashMap<Int, TranslatedBlock>()
        if (longBlocks.isNotEmpty()) {
            val tLong = System.currentTimeMillis()
            var droppedLong = 0
            for (b in longBlocks) {
                val chunks = TextChunker.chunk(b.english)
                val parts = arrayOfNulls<String>(chunks.size)
                var failedChunk = -1
                for ((index, c) in chunks.withIndex()) {
                    // P2-06: one chunk failure used to abort the whole page. Now the chunk is
                    // retried once, and if it still fails only THIS bubble is left empty.
                    var out = translateChunkText(b.id, index, c)
                    if (out == null) out = translateChunkText(b.id, index, c)
                    if (out == null) {
                        failedChunk = index
                        break
                    }
                    parts[index] = out
                }
                // A half-answered narration is never returned as a complete bubble (see
                // MlKitPageAssembly.joinChunks): the block stays empty instead.
                val joined = if (failedChunk >= 0) null else MlKitPageAssembly.joinChunks(parts.toList())
                if (joined == null) {
                    droppedLong++
                    PipelineTracer.span(
                        "mlkit long block left empty", 0,
                        "block #${b.id}: chunk ${failedChunk + 1}/${chunks.size} unanswered — bubble empty, rest of the page kept"
                    )
                    continue
                }
                val tb = TranslatedBlock(b.id, b.english, joined)
                answered[b.id] = tb
                emitReady(tb)
            }
            PipelineTracer.span(
                "mlkit translate long-block chunks",
                System.currentTimeMillis() - tLong,
                "${longBlocks.size} long block(s)" + if (droppedLong > 0) ", $droppedLong left empty" else ""
            )
        }

        // 3) Main strategy for the rest.
        val tTranslate = System.currentTimeMillis()
        if (normal.isNotEmpty()) {
            PipelineTracer.span("mlkit warm state", 0, MlKitWarmth.describe(System.currentTimeMillis()))
            val policy = BatchPolicyHolder.instance
            var done = false
            if (onBlockReady == null && normal.size >= 2) {
                // Joined single call ONLY with a separator proven on this device.
                val strategy = policy?.current()
                    ?.takeIf { normal.sumOf { b -> b.english.length } <= JOINED_MAX_CHARS }
                    // names are already Arabic here: check the marker against the prepared text
                    ?.takeIf { s -> BatchSplitter.sourcesSafe(s, normal.map { b -> src(b.english) }) }
                if (strategy != null) {
                    val joined = translateJoined(normal, strategy)
                    val ms = System.currentTimeMillis() - tTranslate
                    if (joined.blocks != null) {
                        PipelineTracer.span("mlkit translate joined", ms, "${normal.size} blocks, 1 call · strategy=joined ${strategy.label}")
                        joined.blocks.forEach { answered[it.id] = it }
                        done = true
                    } else if (joined.engineError) {
                        // Engine error, not a mapping problem — nothing to remember.
                        PipelineTracer.span("mlkit joined error", ms, "${joined.reason} — redoing page in parallel")
                    } else {
                        policy.noteJoinedFailure(strategy, joined.reason)
                        maybeStartSelfTest()
                        Log.w(TAG, "joined ${strategy.label} mismatch (${joined.reason}) — separator banned for 7 days")
                        PipelineTracer.span(
                            "mlkit joined FAILED", ms,
                            "strategy=${strategy.label} ${joined.reason} — separator banned for 7 days, self-test will pick another; page redone in parallel"
                        )
                    }
                }
            }
            if (!done) {
                // Release default: per block, ≤ 3 in flight (progressive listener fed per completion).
                val tParallel = System.currentTimeMillis()
                val parallel = translateParallel(normal)
                PipelineTracer.span(
                    if (onBlockReady != null) "mlkit translate progressive" else "mlkit translate parallel",
                    System.currentTimeMillis() - tParallel,
                    "${normal.size} blocks, ${parallelLevel()} in flight · strategy=${policy?.describe() ?: "parallel (policy not attached)"}"
                )
                parallel.forEach { answered[it.id] = it }
                // P2-06: the sequential pass is a RETRY of the unanswered ids only — it never
                // re-sends a bubble that already has an answer, and a bubble that still fails is
                // left empty instead of failing the whole page.
                val unanswered = normal.filter { block ->
                    val done = answered[block.id]
                    done == null || done.arabic.isBlank()
                }
                if (unanswered.isNotEmpty()) {
                    PipelineTracer.span("mlkit retry", 0, "re-asking ${unanswered.size} unanswered block(s) one by one")
                    val recovered = MlKitPageAssembly.retryMissing(
                        unanswered,
                        answered.mapValues { it.value.arabic },
                        ::attemptBlock
                    )
                    for (b in unanswered) {
                        val text = recovered[b.id]
                        if (text.isNullOrBlank()) {
                            PipelineTracer.span(
                                "mlkit block left empty", 0,
                                "block #${b.id} produced no Arabic after retry — left empty (never the English source)"
                            )
                        } else {
                            val tb = TranslatedBlock(b.id, b.english, text)
                            answered[b.id] = tb
                            emitReady(tb)
                        }
                    }
                }
            }
            // First use on this device: prove (or rule out) a joined separator in the background.
            maybeStartSelfTest()
        }

        // Preserve original page order and blanks. P0/C2: a block without an answer stays
        // EMPTY — the English source is never substituted (see MlKitPageAssembly.ordered).
        // P2-06: the page is a SUCCESS as long as one bubble came back; only a page with
        // nothing usable at all is a failure.
        val arabicById = answered.mapValues { it.value.arabic }
        pageNames = emptyMap()
        val assembly = MlKitPageAssembly.assemble(blocks, arabicById)
        val elapsed = System.currentTimeMillis() - start
        if (MlKitPageAssembly.mustFail(assembly, translatable.size)) {
            Log.e("ManhwaLens", "ML Kit page failed: 0 of ${translatable.size} block(s) usable")
            PipelineTracer.span("mlkit page failed", elapsed, "0 of ${translatable.size} block(s) usable")
            return PageTranslation.Failure(
                FailureReason.UNKNOWN,
                "On-device translation failed",
                engineId
            )
        }
        if (assembly.unansweredIds.isNotEmpty()) {
            PipelineTracer.span(
                "mlkit page partial", elapsed,
                "${assembly.translated}/${translatable.size} block(s) translated · empty: ${assembly.unansweredIds}"
            )
        }
        Log.i(TAG, "ML Kit batch done: ${assembly.blocks.size} blocks in $elapsed ms (${assembly.translated} translated)")
        return PageTranslation.Success(
            blocks = assembly.blocks,
            engineId = engineId,
            elapsedMs = elapsed,
            cacheHits = 0,
            cacheMisses = 0
        )
    }

    private class JoinedOutcome(val blocks: List<TranslatedBlock>?, val engineError: Boolean, val reason: String)

    /** ONE joined call with a proven [strategy]; fully validated. */
    private fun translateJoined(blocks: List<TranslationBlock>, strategy: SplitStrategy): JoinedOutcome {
        val sources = blocks.map { src(it.english) } // same text the engine is shown, so the mapping holds
        val out = try {
            Tasks.await(client.translate(BatchSplitter.join(strategy, sources)), TRANSLATE_TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.e(TAG, "mlkit joined request failed — ${e.javaClass.name}: ${e.message}", e)
            return JoinedOutcome(null, true, "error: ${e.javaClass.simpleName}")
        }
        val v = BatchSplitter.validate(strategy, sources, out)
        if (!v.ok) return JoinedOutcome(null, false, "mismatch(${v.mismatched}): ${v.reason}")
        return JoinedOutcome(blocks.mapIndexed { i, b -> TranslatedBlock(b.id, b.english, v.segments[i]) }, false, "ok")
    }

    /**
     * Fire the progressive listener (worker threads); never throws.
     *
     * P0/C2: the listener paints straight onto the overlay, so the block must pass the shared
     * guard before it leaves the engine. A withheld block is simply not painted now; the final
     * page result (validated again by CachingTranslator) decides what it becomes.
     */
    private fun emitReady(tb: TranslatedBlock) {
        val l = onBlockReady ?: return
        val payload = MlKitPageAssembly.progressivePayload(tb)
        if (payload == null) {
            if (tb.arabic.isNotBlank()) {
                PipelineTracer.span(
                    "mlkit progressive", 0,
                    "block #${tb.id} withheld: output is not usable Arabic"
                )
            }
            return
        }
        runCatching { l(payload) }
    }

    /**
     * Translate each block as one string, at most 3 concurrent ML Kit calls.
     *
     * P2-06: a failing bubble no longer discards the pass. Every answer that did arrive is
     * returned — [MlKitPageAssembly.assemble] restores page order — and the caller retries only
     * the ids that are still missing, one at a time. Cancellation is collected from the worker
     * threads and rethrown on this thread: it must never be reported as a translation failure.
     * Emits each finished block to the progressive listener as it lands.
     */
    private fun translateParallel(blocks: List<TranslationBlock>): List<TranslatedBlock> {
        if (blocks.size <= 1) {
            val b = blocks.firstOrNull() ?: return emptyList()
            val out = attemptBlock(b) ?: return emptyList()
            val tb = TranslatedBlock(b.id, b.english, out)
            emitReady(tb)
            return listOf(tb)
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(parallelLevel())
        val results = arrayOfNulls<String>(blocks.size)
        val cancelled = java.util.concurrent.atomic.AtomicReference<CancellationException?>(null)
        val latch = java.util.concurrent.CountDownLatch(blocks.size)
        try {
            blocks.forEachIndexed { i, b ->
                pool.execute {
                    try {
                        val out = Tasks.await(client.translate(src(b.english)), TRANSLATE_TIMEOUT_SEC, TimeUnit.SECONDS).trim()
                        if (out.isNotBlank()) {
                            results[i] = out
                            emitReady(TranslatedBlock(b.id, b.english, out))
                        }
                    } catch (e: CancellationException) {
                        cancelled.compareAndSet(null, e)
                    } catch (e: Exception) {
                        Log.e(TAG, "mlkit parallel block #${b.id} failed — ${e.javaClass.name}: ${e.message}", e)
                    } finally {
                        latch.countDown()
                    }
                }
            }
            val finished = latch.await((TRANSLATE_TIMEOUT_SEC + 5) * 1000L, TimeUnit.MILLISECONDS)
            if (!finished) {
                Log.e(TAG, "mlkit parallel timed out waiting for ${blocks.size} blocks — keeping the answers that arrived")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.e(TAG, "mlkit parallel interrupted — keeping the answers that arrived")
        } finally {
            pool.shutdownNow()
        }
        cancelled.get()?.let { throw it }
        return blocks.mapIndexedNotNull { i, b ->
            results[i]?.let { TranslatedBlock(b.id, b.english, it) }
        }
    }

    /** One block's engine call for the sequential retry; null when it produced nothing. */
    private fun attemptBlock(block: TranslationBlock): String? = try {
        Tasks.await(client.translate(src(block.english)), TRANSLATE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .trim().takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "mlkit retry failed for block #${block.id} — ${e.javaClass.name}: ${e.message}", e)
        null
    }

    /** One chunk of a long bubble, retried once by the caller; null when the chunk failed. */
    private fun translateChunkText(blockId: Int, index: Int, text: String): String? = try {
        Tasks.await(client.translate(src(text)), TRANSLATE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .trim().takeIf { it.isNotBlank() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "mlkit chunk ${index + 1} of block #$blockId failed — ${e.javaClass.name}: ${e.message}", e)
        null
    }

    private fun failureFor(e: Exception): PageTranslation.Failure =
        PageTranslation.Failure(
            when (e) {
                is TimeoutException -> FailureReason.TIMEOUT
                else -> FailureReason.UNKNOWN
            },
            e.message ?: e.javaClass.simpleName,
            engineId
        )

    companion object {
        private const val TAG = "MLCapture"

        /** Process-wide single ML Kit EN→AR client — translators are never recreated per tap. */
        private val SHARED_CLIENT: com.google.mlkit.nl.translate.Translator =
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(TranslateLanguage.ENGLISH)
                    .setTargetLanguage(TranslateLanguage.ARABIC)
                    .build()
            )

        /** Lets the service pre-warm set the shared ready flag after download. */
        @JvmStatic
        fun noteModelReady() { sharedModelReady = true }

        /**
         * Service pre-warm hook: verify (or download once) the EN→AR model.
         * Returns true when ready. Bounded to 30 s; safe to call from a background thread.
         */
        @JvmStatic
        fun warmUp(): Boolean = try {
            Tasks.await(SHARED_CLIENT.downloadModelIfNeeded(), 30L, TimeUnit.SECONDS)
            sharedModelReady = true
            // v0.9.9: the model FILE being present is not "warm" — the first translate call
            // still loads it into memory. Pay that here, not on the user's first page.
            keepWarm("service start")
            maybeStartSelfTest()
            true
        } catch (e: Exception) {
            Log.w(TAG, "warmUp: EN→AR model not ready (${e.javaClass.simpleName})")
            false
        }

        /** Raw engine call on the shared client (benchmark, self-test, warm-up). Throws on failure. */
        @JvmStatic
        fun translateRaw(text: String): String =
            Tasks.await(SHARED_CLIENT.translate(text), TRANSLATE_TIMEOUT_SEC, TimeUnit.SECONDS)

        /**
         * Dummy "hello" call that keeps the engine loaded. Called at service start and on
         * screen-on; records cold vs warm timings for the pipeline trace. Returns the call
         * time in ms, or -1 when the model is not available yet / the call failed.
         */
        @JvmStatic
        fun keepWarm(reason: String): Long {
            if (!sharedModelReady) return -1L
            val t0 = System.nanoTime()
            return try {
                translateRaw("hello")
                val ms = (System.nanoTime() - t0) / 1_000_000L
                val wasWarm = MlKitWarmth.isWarm()
                MlKitWarmth.note(ms, reason, System.currentTimeMillis())
                Log.i(TAG, "mlkit warm-up ($reason): ${if (wasWarm) "warm" else "COLD"} call $ms ms")
                ms
            } catch (e: Exception) {
                Log.w(TAG, "mlkit warm-up ($reason) failed — ${e.javaClass.simpleName}")
                -1L
            }
        }

        private val selfTestRunning = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * First use on this device (or after 7 days): 4 short strings per separator, on a
         * background thread; stores the fastest separator that maps 100 %, else "parallel".
         * Pages keep using parallel until a choice exists — no page ever waits for this.
         */
        @JvmStatic
        fun maybeStartSelfTest() {
            val policy = BatchPolicyHolder.instance ?: return
            if (!sharedModelReady || !policy.needsSelfTest()) return
            if (!selfTestRunning.compareAndSet(false, true)) return
            Thread({
                try {
                    val t0 = System.currentTimeMillis()
                    val choice = BatchAutoSelect.run(::translateRaw, strategies = policy.allowedStrategies())
                    policy.recordChoice(choice.strategy, choice.why)
                    lastSelfTestSummary = choice.trials.joinToString(" · ") {
                        "${it.strategy.label}=${if (it.ok) "${it.elapsedMs}ms" else "FAIL(${it.mismatched})"}"
                    } + " · parallel=${choice.parallelMs}ms"
                    Log.i(TAG, "batch self-test in ${System.currentTimeMillis() - t0} ms → ${policy.describe()} [$lastSelfTestSummary]")
                } catch (t: Throwable) {
                    Log.w(TAG, "batch self-test failed — staying parallel (${t.javaClass.simpleName})")
                } finally {
                    selfTestRunning.set(false)
                }
            }, "manhwalens-batch-selftest").apply { isDaemon = true }.start()
        }

        /** Per-separator result of the last self-test in this process ("" when none ran). */
        @Volatile
        var lastSelfTestSummary: String = ""
            private set

        /** Joined calls are skipped for very large pages (per-block is safer there). */
        private const val JOINED_MAX_CHARS = 3000

        private const val MODEL_TIMEOUT_SEC = 30L
        private const val TRANSLATE_TIMEOUT_SEC = 15L
        /** Per-block calls in flight: the benchmarked level for this device, default 2. */
        private fun parallelLevel(): Int =
            BatchPolicyHolder.instance?.parallelLevel() ?: com.manhwalens.app.translate.BatchPolicy.DEFAULT_PARALLEL

        /**
         * Process-wide readiness flag: the EN→AR model download is one-time, so
         * after the first successful check no instance should keep hitting the
         * download path (or show the "downloading…" pill) again.
         */
        @Volatile
        private var sharedModelReady = false


        /** True after the EN→AR model was downloaded/available at least once this process. */
        fun isModelReady(): Boolean = sharedModelReady
    }
}
