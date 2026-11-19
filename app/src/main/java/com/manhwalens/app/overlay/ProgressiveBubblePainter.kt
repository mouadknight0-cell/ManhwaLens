package com.manhwalens.app.overlay

import android.graphics.Bitmap
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import com.manhwalens.app.ocr.BubbleBlock
import com.manhwalens.app.translate.TranslatedBlock
import com.manhwalens.app.render.BubbleRenderBox
import com.manhwalens.app.render.BubblePatch
import com.manhwalens.app.render.PatchPlanner
import com.manhwalens.app.util.EndToEndLatency
import com.manhwalens.app.util.PipelineTracer
import com.manhwalens.app.util.StageTrace

/**
 * Progressive overlay painter (Part C): holds the frame + the filtered blocks,
 * samples each bubble's background lazily on first paint (bubble-sized pixels —
 * cheap), and pushes a growing box list to the overlay as translations land:
 *  - onBlockReady (ML Kit, per block, ≤3 in flight, priority order)
 *  - onRemote (selected remote engine runs beside ML Kit; a valid remote reply wins)
 *  - finish() (final reconciliation once the page translation returns)
 *
 * Thread-safe: paint may arrive from worker threads; overlay ops post to main.
 * [attached] flips true the moment the first box hits the screen.
 */
internal class ProgressiveBubblePainter(
    private val context: android.content.Context,
    private val mainHandler: android.os.Handler,
    private val bitmap: Bitmap,
    keptBlocks: List<BubbleBlock>,
    private val tapStartMs: Long,
    private val engineId: String,
    private val diagnostics: com.manhwalens.app.util.CaptureDiagnostics?,
    private val sampleBackground: (Bitmap, Int, Int, Int, Int) -> Int,
    private val buildPatches: (Bitmap, List<com.manhwalens.app.render.BubbleRenderBox>, List<com.manhwalens.app.render.PatchPlanner.Area>?) -> List<com.manhwalens.app.render.BubblePatch>,
    private val ensureOverlay: () -> com.manhwalens.app.overlay.PatchOverlayWindow,
    private val hideStatus: () -> Unit
) {
    private val lock = Any()
    private val blocksById = keptBlocks.associateBy { it.id }
    /**
     * v0.11.0: block id → its area's bounds. Owner and sibling both resolve their area
     * from its members' bounds, not from the owner's own OCR box.
     */
    private val areaById = HashMap<Int, com.manhwalens.app.ocr.geometry.Rect2D>()
    private val painted = LinkedHashMap<Int, com.manhwalens.app.render.BubbleRenderBox>()
    /** Original per-block payloads; composite patches are rebuilt from this map. */
    private val renderBoxesById = LinkedHashMap<Int, com.manhwalens.app.render.BubbleRenderBox>()
    /** Monotonic generation per area prevents an older worker build replacing newer text. */
    private val areaGeneration = HashMap<Int, Int>()
    /** Last generation successfully rasterized; finish retries only missing/failed areas. */
    private val builtGenerationByArea = HashMap<Int, Int>()
    private val areaPlanById = HashMap<Int, com.manhwalens.app.render.PatchPlanner.Area>()
    private val remoteWins = HashSet<Int>()
    /**
     * v0.13.0 — single-flight raster builds per area. Sibling blocks of one visual bubble
     * often land within milliseconds; the old code rasterized a full opaque patch per
     * block and discarded all but the newest on the main thread. Now at most one build
     * per area runs at a time: paints that arrive during a build set a pending flag and
     * trigger exactly one rebuild with the latest composite.
     */
    private val buildingAreas = HashSet<Int>()
    private val pendingAreas = HashSet<Int>()
    /** Latest translation per area owner, so a coalesced rebuild paints the newest text. */
    private val latestByArea = HashMap<Int, Pair<com.manhwalens.app.translate.TranslatedBlock, Boolean>>()
    /**
     * One patch area per visual bubble, decided up-front from EVERY kept block. Each
     * update composes all translations known for that area into its owner's stable patch ID.
     */
    private val areas: List<com.manhwalens.app.render.PatchPlanner.Area> =
        com.manhwalens.app.render.PatchPlanner.areas(
            keptBlocks.map {
                it.id to com.manhwalens.app.ocr.geometry.Rect2D(
                    it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom
                )
            },
            bitmap.width, bitmap.height
        )
    init {
        // Every member of an area resolves to the same cover rect.
        for (a in areas) for (id in a.memberIds) {
            areaById[id] = a.cover
            areaPlanById[id] = a
        }
    }
    private val paintLedger = com.manhwalens.app.render.PaintLedger(keptBlocks.size)

    @Volatile var attached: Boolean = false
        private set

    /** Set on the worker thread the instant the first attach is queued (race-free visible flag). */
    @Volatile var attachRequested: Boolean = false
        private set

    @Volatile var firstBubbleMs: Long = -1L
        private set

    fun onBlockReady(tb: com.manhwalens.app.translate.TranslatedBlock) = paint(tb, remote = false)
    fun onRemote(tb: com.manhwalens.app.translate.TranslatedBlock) = paint(tb, remote = true)

    private fun paint(tb: com.manhwalens.app.translate.TranslatedBlock, remote: Boolean) {
        if (tb.arabic.isBlank()) return
        val block = blocksById[tb.id] ?: return
        val verdict = com.manhwalens.app.translate.ArabicValidator.issue(block.text, tb.arabic)
        if (verdict != null) {
            Log.w(TAG, "patch refused #${tb.id} (${verdict.name.lowercase()}): output is not usable Arabic")
            return
        }

        val r = block.bounds
        val left = r.left.toInt().coerceIn(0, bitmap.width)
        val top = r.top.toInt().coerceIn(0, bitmap.height)
        val right = r.right.toInt().coerceIn(0, bitmap.width)
        val bottom = r.bottom.toInt().coerceIn(0, bitmap.height)
        val bg = sampleBackground(bitmap, left, top, right, bottom)
        val box = com.manhwalens.app.render.BubbleRenderBox(
            id = tb.id,
            left = left, top = top, right = right, bottom = bottom,
            english = block.text,
            arabic = tb.arabic,
            backgroundColor = bg,
            textColor = com.manhwalens.app.render.ColorSampler.bestTextColorOn(bg)
        )

        val areaPlan = areaPlanById[tb.id]
        val ownerId = areaPlan?.ownerId ?: tb.id
        val cover = areaPlan?.cover
        val textBox = if (cover != null) {
            box.copy(
                left = cover.left.toInt().coerceIn(0, bitmap.width),
                top = cover.top.toInt().coerceIn(0, bitmap.height),
                right = cover.right.toInt().coerceIn(0, bitmap.width),
                bottom = cover.bottom.toInt().coerceIn(0, bitmap.height)
            )
        } else box

        synchronized(lock) {
            if (!remote && remoteWins.contains(tb.id)) return
            if (remote) remoteWins.add(tb.id)
            renderBoxesById[tb.id] = box
            painted[tb.id] = textBox
            paintLedger.record(tb.id)
            latestByArea[ownerId] = tb to remote
            val generation = (areaGeneration[ownerId] ?: 0) + 1
            areaGeneration[ownerId] = generation
        }
        Log.i(TAG, paintLedger.line())
        scheduleBuild(ownerId)
    }

    /**
     * v0.13.0 — at most one raster build per area at a time. A paint that arrives while
     * its area is already building only sets the pending flag; the in-flight build loop
     * then rebuilds once with the newest composite instead of every intermediate one.
     */
    private fun scheduleBuild(ownerId: Int) {
        synchronized(lock) {
            if (!buildingAreas.add(ownerId)) {
                pendingAreas.add(ownerId)
                return
            }
        }
        try {
            while (true) {
                buildAndPost(ownerId)
                val more = synchronized(lock) { pendingAreas.remove(ownerId) }
                if (!more) break
            }
        } finally {
            synchronized(lock) { buildingAreas.remove(ownerId) }
        }
    }

    /** One raster build + main-thread post for [ownerId], from the latest known state. */
    private fun buildAndPost(ownerId: Int) {
        val snap = synchronized(lock) {
            val latest = latestByArea[ownerId] ?: return
            val tb = latest.first
            val remote = latest.second
            val block = blocksById[tb.id] ?: return
            val plan = areaPlanById[tb.id] ?: com.manhwalens.app.render.PatchPlanner.Area(
                ownerId = tb.id,
                memberIds = listOf(tb.id),
                cover = com.manhwalens.app.ocr.geometry.Rect2D(
                    block.bounds.left, block.bounds.top, block.bounds.right, block.bounds.bottom
                )
            )
            val members = plan.memberIds.mapNotNull { renderBoxesById[it] }
            val composed = com.manhwalens.app.render.PatchPlanner.compose(listOf(plan), members)
                .firstOrNull() ?: members.firstOrNull() ?: return
            // Use a stable sample from the area's owner, not whichever sibling happened
            // to finish first; this keeps flood expansion and patch geometry deterministic.
            // Single-member area: the owner IS this block, so its already-sampled
            // background is identical — skip the duplicate sample.
            val ownerBg = if (plan.memberIds.size == 1) {
                members.first().backgroundColor
            } else {
                val ownerBlock = blocksById[ownerId] ?: block
                val ob = ownerBlock.bounds
                sampleBackground(
                    bitmap,
                    ob.left.toInt().coerceIn(0, bitmap.width),
                    ob.top.toInt().coerceIn(0, bitmap.height),
                    ob.right.toInt().coerceIn(0, bitmap.width),
                    ob.bottom.toInt().coerceIn(0, bitmap.height)
                )
            }
            val composite = composed.copy(
                backgroundColor = ownerBg,
                textColor = com.manhwalens.app.render.ColorSampler.bestTextColorOn(ownerBg)
            )
            BuildSnap(
                composite = composite,
                cover = plan.cover,
                memberIds = plan.memberIds,
                generation = areaGeneration[ownerId] ?: 0,
                tb = tb,
                remote = remote,
                block = block
            )
        }
        val composite = snap.composite
        val cover = snap.cover
        val generation = snap.generation
        val tb = snap.tb
        val remote = snap.remote
        val block = snap.block

        // One opaque bitmap per area: it contains every sibling translation known so far.
        val patch = try {
            com.manhwalens.app.render.PatchSurface.build(
                context.applicationContext, bitmap, composite, cover = cover, band = null
            )
        } catch (oom: OutOfMemoryError) {
            Log.e("ManhwaLens", "patch build OOM for area #$ownerId — bubble skipped", oom)
            null
        } catch (e: Exception) {
            Log.e("ManhwaLens", "patch build failed for area #$ownerId — ${e.javaClass.name}: ${e.message}", e)
            null
        }
        if (patch != null) {
            synchronized(lock) {
                if (areaGeneration[ownerId] == generation) {
                    builtGenerationByArea[ownerId] = generation
                }
            }
            diagnostics?.recordOverlayText(
                tb.id, block.text, tb.arabic, if (remote) "remote" else engineId, patchBuilt = true
            )
            diagnostics?.recordOverlayPatch(
                ownerId, patch.left, patch.top, patch.right, patch.bottom,
                cover, snap.memberIds
            )
        }
        val elapsed = SystemClock.elapsedRealtime() - tapStartMs
        if (patch != null && !attached && !attachRequested) attachRequested = true
        mainHandler.post {
            if (patch == null) return@post
            val stillLatest = synchronized(lock) { areaGeneration[ownerId] == generation }
            if (!stillLatest) {
                runCatching { patch.bitmap.recycle() }
                return@post
            }
            hideStatus()
            val po = ensureOverlay()
            if (!attached) {
                attached = true
                firstBubbleMs = elapsed
                // v0.13.0: T6 — first complete Arabic sentence on screen.
                EndToEndLatency.mark(EndToEndLatency.Event.T6_FIRST_SENTENCE_DISPLAYED)
                po.beginSession("page change")
                if (firstBubbleMs >= 0) {
                    PipelineTracer.span(
                        "first bubble shown at", firstBubbleMs,
                        "patch 1 painted (${if (remote) "remote" else engineId})"
                    )
                }
                PipelineTracer.record(
                    PipelineTracer.Stage.OVERLAY, StageTrace.Status.OK, 0L,
                    "transparent patch overlay attached (tap→overlay ${firstBubbleMs} ms)"
                )
            }
            if (po.patchRect(ownerId) != null) {
                po.replaceBitmap(ownerId, patch.bitmap)
            } else {
                po.upsert(patch, bitmap.width, bitmap.height)
            }
        }
    }

    /** Immutable snapshot of one area's latest state for a single raster build. */
    private data class BuildSnap(
        val composite: com.manhwalens.app.render.BubbleRenderBox,
        val cover: com.manhwalens.app.ocr.geometry.Rect2D?,
        val memberIds: List<Int>,
        val generation: Int,
        val tb: com.manhwalens.app.translate.TranslatedBlock,
        val remote: Boolean,
        val block: BubbleBlock
    )

    fun snapshot(): List<com.manhwalens.app.render.BubbleRenderBox> =
        synchronized(lock) {
            com.manhwalens.app.render.PatchPlanner.compose(areas, painted.values.toList())
                .sortedBy { it.top }
        }

    /** Fill anything not painted yet (cache-hit path paints earlier; engine call paints via listener). */
    fun finish(translations: List<com.manhwalens.app.translate.TranslatedBlock>) {
        synchronized(lock) {
            for (t in translations) {
                if (t.arabic.isBlank() || painted.containsKey(t.id) || remoteWins.contains(t.id)) continue
                val block = blocksById[t.id] ?: continue
                val verdict = com.manhwalens.app.translate.ArabicValidator.issue(block.text, t.arabic)
                if (verdict != null) continue
                val r = block.bounds
                val left = r.left.toInt().coerceIn(0, bitmap.width)
                val top = r.top.toInt().coerceIn(0, bitmap.height)
                val right = r.right.toInt().coerceIn(0, bitmap.width)
                val bottom = r.bottom.toInt().coerceIn(0, bitmap.height)
                val bg = sampleBackground(bitmap, left, top, right, bottom)
                val box = com.manhwalens.app.render.BubbleRenderBox(
                    id = t.id,
                    left = left, top = top, right = right, bottom = bottom,
                    english = block.text,
                    arabic = t.arabic,
                    backgroundColor = bg,
                    textColor = com.manhwalens.app.render.ColorSampler.bestTextColorOn(bg)
                )
                renderBoxesById[t.id] = box
                val area = areaById[t.id]
                painted[t.id] = if (area != null) {
                    box.copy(
                        left = area.left.toInt(), top = area.top.toInt(),
                        right = area.right.toInt(), bottom = area.bottom.toInt()
                    )
                } else box
                paintLedger.record(t.id)
                val ownerId = areaPlanById[t.id]?.ownerId ?: t.id
                areaGeneration[ownerId] = (areaGeneration[ownerId] ?: 0) + 1
            }
        }
        Log.i(TAG, paintLedger.line())
        // Snapshot text and generations atomically. A remote upgrade arriving between
        // these reads could otherwise make an old snapshot look current and overwrite it.
        val state = synchronized(lock) {
            Triple(
                com.manhwalens.app.render.PatchPlanner.compose(areas, painted.values.toList())
                    .sortedBy { it.top },
                areaGeneration.toMap(),
                areas.filter { area ->
                    val current = areaGeneration[area.ownerId] ?: 0
                    val built = builtGenerationByArea[area.ownerId] ?: -1
                    current > 0 && built != current
                }
            )
        }
        val snap = state.first
        val generationSnapshot = state.second
        val staleAreas = state.third
        if (snap.isEmpty() || staleAreas.isEmpty()) return
        if (!attachRequested) attachRequested = true

        // Retry only areas whose latest composite failed or was superseded. Successful
        // progressive composites are already on screen and need no second raster pass.
        val patches = buildPatches(bitmap, snap, staleAreas)
        val builtIds = patches.map { it.id }.toHashSet()
        snap.filter { box ->
            val owner = com.manhwalens.app.render.PatchPlanner.ownerOf(staleAreas, box.id)
            owner in builtIds
        }.forEach { box ->
            diagnostics?.recordOverlayText(
                box.id, box.english, box.arabic, "final-reconcile", patchBuilt = true
            )
        }
        patches.forEach { patch ->
            val area = staleAreas.firstOrNull { it.ownerId == patch.id }
            diagnostics?.recordOverlayPatch(
                patch.id, patch.left, patch.top, patch.right, patch.bottom,
                area?.cover, area?.memberIds ?: listOf(patch.id)
            )
        }
        val elapsed = SystemClock.elapsedRealtime() - tapStartMs
        mainHandler.post {
            if (patches.isEmpty()) return@post
            val po = ensureOverlay()
            if (!attached) {
                attached = true
                firstBubbleMs = elapsed
                // v0.13.0: T6 — first complete Arabic sentence on screen.
                EndToEndLatency.mark(EndToEndLatency.Event.T6_FIRST_SENTENCE_DISPLAYED)
                po.beginSession("page change")
                PipelineTracer.span(
                    "first bubble shown at", firstBubbleMs,
                    "patch 1 painted (final reconciliation)"
                )
                PipelineTracer.record(
                    PipelineTracer.Stage.OVERLAY, StageTrace.Status.OK, 0L,
                    "transparent patch overlay attached (tap→overlay ${firstBubbleMs} ms)"
                )
            }
            for (patch in patches) {
                val expected = generationSnapshot[patch.id] ?: 0
                val stillLatest = synchronized(lock) { areaGeneration[patch.id] == expected }
                if (!stillLatest) {
                    runCatching { patch.bitmap.recycle() }
                    continue
                }
                if (po.patchRect(patch.id) != null) {
                    po.replaceBitmap(patch.id, patch.bitmap)
                } else {
                    po.upsert(patch, bitmap.width, bitmap.height)
                }
            }
            Log.i(TAG, "painter finish: reconciled ${patches.size} composite area(s) (elapsed=${elapsed} ms)")
        }
    }

    private companion object {
        const val TAG = "MLCapture"
    }
}
