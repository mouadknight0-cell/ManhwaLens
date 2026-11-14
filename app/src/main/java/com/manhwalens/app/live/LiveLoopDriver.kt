package com.manhwalens.app.live

import java.util.Locale

/**
 * Pure-JVM tick engine for Live mode (v0.9.4 · Part A).
 *
 * WHY THIS EXISTS
 * MediaProjection only delivers a frame when the screen CHANGES. The v0.9.3 tick
 * called `tracker.onFrame()` only when a fresh frame was acquired and threw the
 * frame away whenever patches were on screen, so once the page went still the
 * stability window could never elapse: `Changing → Settling → Stable` needs time,
 * and time only advanced when a new frame arrived. Result: nothing was translated
 * after a scroll.
 *
 * WHAT IT DOES
 *  - keeps ONE stash: last frame + its thumbnail + `stashPatchesBurnedIn`
 *    (true when a patch was visible/fading AND the overlay was drawn when the
 *    frame was captured);
 *  - updates the stash on EVERY acquired frame, recycling only the previous one —
 *    a frame is never discarded merely because patches exist;
 *  - feeds the tracker on EVERY tick, reusing the stashed thumbnail (diff 0) when
 *    no new frame arrived, so the verdict advances with wall-clock time;
 *  - never hands a possibly burned-in frame to OCR: on `Stable` it uses the stash
 *    when clean, otherwise hides the overlay and waits (bounded) for a REAL new
 *    frame; on timeout it aborts the cycle and retries on the next tick;
 *  - reports starvation and frame-flow diagnostics so the behaviour is provable.
 *
 * No Android types: frames are opaque [F] handles owned by [FrameSource].
 * All view/window side effects leave through [Sink] (the service implements both).
 */
class LiveLoopDriver<F : Any>(
    private val source: FrameSource<F>,
    private val sink: Sink<F>,
    private val clock: () -> Long = { System.currentTimeMillis() },
    var stableWindowMs: Long = DEFAULT_STABLE_WINDOW_MS,
    var changeThreshold: Float = DEFAULT_CHANGE_THRESHOLD
) {

    /** Everything the driver needs from the capture pipeline. Implemented by the service. */
    interface FrameSource<F : Any> {
        /** Newest frame produced since the last call; null when the screen went still. */
        fun acquireNewFrame(): F?

        /** Grayscale thumbnail (THUMB_W × THUMB_H luma bytes) of [frame]. */
        fun grayOf(frame: F): ByteArray

        /** Cells excluded from the frame diff (our patches + the lens menu rect); null = none. */
        fun diffMask(): BooleanArray?

        /**
         * True when patches are on screen right now, INCLUDING ones still fading out
         * (80 ms) — regardless of whether the overlay window is currently drawn.
         */
        fun hasPatchesOnScreen(): Boolean

        /**
         * True when a frame captured RIGHT NOW would carry our own Arabic burned in,
         * i.e. patches exist (visible or fading) AND the overlay is actually drawn.
         */
        fun patchesBurnedInNow(): Boolean

        /** Visible / fading patch counts for the debug panel. */
        fun patchCounts(): PatchCounts

        /** Release (recycle) a frame the driver no longer owns. */
        fun releaseFrame(frame: F)

        /**
         * Patches must ride the scroll: shift them by [dyThumbnailRows] thumbnail rows.
         * @return true when at least one patch actually moved.
         */
        fun onShiftPatches(frame: F, dyThumbnailRows: Int, confidence: Float): Boolean

        /** Hide the patch overlay (INVISIBLE) and wait for the hide to be applied. */
        fun hideOverlay(): Boolean

        /** Wait up to [timeoutMs] for a REAL new frame (the mirror redraw after the hide). */
        fun awaitNewFrame(timeoutMs: Long): F?

        /** Restore the patch overlay. */
        fun showOverlay()
    }

    /** Everything the driver tells the pipeline to do. Implemented by the service. */
    interface Sink<F : Any> {
        /** Coarse state for the lens ring/dot + caption. */
        fun onStatus(status: DriverStatus)

        /**
         * A stable, not-yet-translated page: run OCR/translate on [frame].
         * Ownership of [frame] moves to the callee (it must recycle it).
         * [frameOrigin] is "stash" or "fresh" (for the trace).
         */
        fun onTranslate(frame: F, gray: ByteArray, frameOrigin: String)

        /** Patches must be cleared; [reason] is the removal reason logged/traced. */
        fun onClearPatches(reason: String)

        /** Patches were shifted with a scroll or cleared → same-page memory invalidated. */
        fun onPatchesMovedOrCleared()

        /**
         * No frame has EVER arrived [elapsedMs] into the session → recreate the
         * VirtualDisplay once and surface "No frames yet".
         */
        fun onNoFramesYet(elapsedMs: Long)

        /** Changing/Settling persists with no new frames (throttled to 1/second). */
        fun onStarved(msSinceLastFrame: Long, verdict: LiveStabilityTracker.Verdict)

        /** A clean-capture attempt timed out; the cycle is aborted and retried next tick. */
        fun onCleanCaptureTimeout(consecutiveAttempts: Int)

        /** Fresh diagnostics snapshot (debug panel / trace). */
        fun onDiagnostics(diagnostics: Diagnostics)

        /** Free-form trace line for the debug panel. */
        fun trace(stage: String, detail: String)

        /** A tick-level failure that did not throw (thumbnail conversion, etc.). */
        fun onError(what: String)

        /**
         * Part 1f (v0.9.5): an exception a `runCatching` absorbed inside the driver. Nothing
         * in the live path may disappear silently — the callee logs class + message + stack.
         */
        fun onSwallowed(where: String, error: Throwable) {
            onError("swallowed in $where — ${error.javaClass.name}: ${error.message}")
        }
    }

    /**
     * Run [block], absorbing throwables but reporting them through [Sink.onSwallowed]
     * (Part 1f). Returns null when the block threw.
     */
    private inline fun <T> guard(where: String, block: () -> T): T? =
        try {
            block()
        } catch (t: Throwable) {
            sink.onSwallowed(where, t)
            null
        }

    enum class DriverStatus { SCANNING, WAITING, TRANSLATING, SKIPPED }

    /** Where this tick's thumbnail came from. */
    enum class FrameOrigin(val wire: String) { NEW("new"), STASH("stash"), NONE("none") }

    /** State of the single frame stash. */
    enum class StashState(val wire: String) { NONE("none"), CLEAN("clean"), BURNED("burned") }

    data class PatchCounts(val visible: Int, val fading: Int) {
        val total: Int get() = visible + fading
        fun wire(): String = "visible=$visible fading=$fading"
    }

    /** Frame-flow half of the Part D diagnostics (the service adds projection/prefs). */
    data class Diagnostics(
        val framesPerSecond: Float,
        val framesReceived: Long,
        val ticksWithoutNewFrame: Long,
        val frameOrigin: FrameOrigin,
        val verdict: String,
        val diff: Float,
        val msSinceChange: Long,
        val patches: PatchCounts,
        val stash: StashState,
        val stashHoldsFrame: Boolean,
        val cleanCaptureTimeouts: Long,
        val samePageSkips: Long
    ) {
        fun lines(): List<String> = listOf(
            "frames/s=${fmt(framesPerSecond)} total=$framesReceived idleTicks=$ticksWithoutNewFrame source=${frameOrigin.wire}",
            "verdict=$verdict diff=${fmt(diff)} sinceChange=${msSinceChange}ms",
            "patches(${patches.wire()}) stash=${stash.wire}${if (stashHoldsFrame) "" else "(thumb-only)"}",
            "cleanCaptureTimeouts=$cleanCaptureTimeouts samePageSkips=$samePageSkips"
        )
    }

    private val tracker = LiveStabilityTracker(changeThreshold, stableWindowMs)

    // ---- the ONE stash ----
    private var stashFrame: F? = null
    private var stashGray: ByteArray? = null
    private var stashPatchesBurnedIn: Boolean = false

    // ---- same-page memory ----
    private var lastAttemptedThumb: ByteArray? = null
    private var lastTranslatedThumb: ByteArray? = null

    // ---- timing / counters ----
    private var sessionStartMs = 0L
    private var lastNewFrameAtMs = 0L
    private var everSawFrame = false
    private var displayRecreateRequested = false
    private var ticksWithoutNewFrame = 0L
    private var framesReceived = 0L
    private var framesInWindow = 0
    private var fpsWindowStartMs = 0L
    private var framesPerSecond = 0f
    private var cleanCaptureTimeouts = 0L
    private var consecutiveCleanTimeouts = 0
    private var samePageSkips = 0L
    private var lastStarvedLogMs = 0L
    private var lastVerdict: LiveStabilityTracker.Verdict? = null
    private var lastOrigin: FrameOrigin = FrameOrigin.NONE
    private var lastDiagnostics: Diagnostics? = null

    /** Session (re)start: forget the stash timing so the 3 s no-frame watchdog re-arms. */
    fun startSession(nowMs: Long = clock()) {
        sessionStartMs = nowMs
        lastNewFrameAtMs = nowMs
        everSawFrame = false
        displayRecreateRequested = false
        ticksWithoutNewFrame = 0L
        framesInWindow = 0
        fpsWindowStartMs = nowMs
        framesPerSecond = 0f
        consecutiveCleanTimeouts = 0
        lastStarvedLogMs = 0L
        tracker.reset()
    }

    /**
     * Tracker-only reset (battery-standby wake-up): forget the previous thumbnail so the
     * next frame reads as a change, but KEEP the stash — the probed frame is stashed right
     * after this call and a still screen may never emit another one.
     */
    fun resetTracker() {
        tracker.reset()
        lastVerdict = null
        consecutiveCleanTimeouts = 0
    }

    /**
     * Take ownership of a frame acquired OUTSIDE a tick (battery-standby probe) and stash it,
     * so the next tick can use it even when the mirror emits nothing more.
     */
    @Synchronized
    fun adoptFrame(frame: F, gray: ByteArray) {
        val now = clock()
        if (sessionStartMs <= 0L) startSession(now)
        framesInWindow++
        framesReceived++
        lastNewFrameAtMs = now
        ticksWithoutNewFrame = 0L
        everSawFrame = true
        updateStash(frame, gray, source.patchesBurnedInNow())
    }

    /** Full reset (screen on/off, resume from standby, live toggled): stash + memory + tracker. */
    fun reset() {
        releaseStash()
        invalidateSamePage()
        lastTranslatedThumb = null
        tracker.reset()
        lastVerdict = null
        ticksWithoutNewFrame = 0L
        consecutiveCleanTimeouts = 0
    }

    /** Drop the stashed frame (recycled through the source); the thumbnail is kept. */
    @Synchronized
    fun releaseStash() {
        val f = stashFrame
        stashFrame = null
        if (f != null) guard("releaseFrame") { source.releaseFrame(f) }
    }

    /**
     * Patches moved with a scroll or were cleared → the next Stable pass must NOT be
     * skipped as "same page" (the visible page no longer matches what we attempted).
     */
    fun invalidateSamePage() {
        lastAttemptedThumb = null
    }

    /** Called by the pipeline once a page has actually been translated. */
    fun noteTranslated(gray: ByteArray) {
        lastTranslatedThumb = gray
    }

    /** Standby wake-up check: does [gray] differ from the last known page? */
    fun changedSinceLastKnown(gray: ByteArray): Boolean {
        val base = lastAttemptedThumb ?: lastTranslatedThumb ?: return true
        return LiveFrameMath.meanAbsoluteDifference(base, gray) > changeThreshold
    }

    /**
     * Hand the stashed frame to an outside consumer (tap capture on a still screen).
     * Returns null when there is no stashed frame or when it may carry burned-in
     * patches — a burned frame must never be analysed. The thumbnail is kept.
     */
    @Synchronized
    fun takeStashedFrameIfClean(): F? {
        val f = stashFrame ?: return null
        if (stashPatchesBurnedIn) return null
        stashFrame = null
        return f
    }

    fun stashState(): StashState = when {
        stashGray == null -> StashState.NONE
        stashPatchesBurnedIn -> StashState.BURNED
        else -> StashState.CLEAN
    }

    fun diagnostics(): Diagnostics? = lastDiagnostics

    /** Absolute ms of the last observed frame change (−1 before the first frame). */
    val lastChangeAtMs: Long get() = tracker.lastChangeAtMs

    /** True when at least one thumbnail is stashed (the tick can run without new frames). */
    fun hasStash(): Boolean = stashGray != null

    // =====================================================================
    // THE TICK
    // =====================================================================

    /**
     * One tick (~3 fps). Returns the delay in ms until the next tick.
     * The caller decides WHETHER live is eligible (live mode, projection, screen
     * on, foreground allow-list, standby); everything after that is this method.
     */
    fun tick(): Long {
        val now = clock()
        if (sessionStartMs <= 0L) startSession(now)
        rollFpsWindow(now)

        val fresh = guard("acquireNewFrame") { source.acquireNewFrame() }

        val origin: FrameOrigin
        val gray: ByteArray
        var newFrame: F? = null

        if (fresh != null) {
            val g = guard("grayOf(new)") { source.grayOf(fresh) }
            if (g == null) {
                guard("releaseFrame(new)") { source.releaseFrame(fresh) }
                return TICK_MS
            }
            framesInWindow++
            framesReceived++
            lastNewFrameAtMs = now
            ticksWithoutNewFrame = 0L
            everSawFrame = true
            // Stash EVERY acquired frame; the previous stash frame is the only one recycled.
            updateStash(fresh, g, source.patchesBurnedInNow())
            origin = FrameOrigin.NEW
            gray = g
            newFrame = fresh
        } else {
            ticksWithoutNewFrame++
            val stashed = stashGray
            if (stashed == null) {
                lastOrigin = FrameOrigin.NONE
                lastVerdict = null
                // No frame has ever arrived: nudge the mirror once after 3 s.
                if (!everSawFrame && !displayRecreateRequested &&
                    now - sessionStartMs >= NO_FRAMES_RECREATE_AFTER_MS
                ) {
                    displayRecreateRequested = true
                    sink.onNoFramesYet(now - sessionStartMs)
                }
                publish(FrameOrigin.NONE, null, now)
                return TICK_MS
            }
            // THE FIX: reuse the stashed thumbnail so the tracker still advances with time.
            origin = FrameOrigin.STASH
            gray = stashed
        }

        val mask = guard("diffMask") { source.diffMask() }
        tracker.excludeMask = mask
        tracker.stableWindowMs = stableWindowMs
        tracker.changeThreshold = changeThreshold
        val prevGray = tracker.lastPrevious()
        val verdict = tracker.onFrame(gray, now)
        lastVerdict = verdict
        lastOrigin = origin

        when (verdict) {
            LiveStabilityTracker.Verdict.Changing -> {
                val shift = if (prevGray != null && newFrame != null) {
                    RowHashShift.estimate(prevGray, gray, LiveFrameMath.THUMB_W, LiveFrameMath.THUMB_H)
                } else null
                val moved = if (shift != null && newFrame != null && source.hasPatchesOnScreen()) {
                    guard("onShiftPatches") { source.onShiftPatches(newFrame, shift.rows, shift.confidence) } ?: false
                } else false
                if (moved && shift != null) {
                    invalidateSamePage()
                    sink.onPatchesMovedOrCleared()
                    sink.trace(
                        "live patch shift",
                        "dy=${shift.rows} rows conf=${fmt(shift.confidence)} — patches moved with scroll (diff=${fmt(tracker.lastDiff)})"
                    )
                } else {
                    val reason = LiveRemovalReason.forFrameChange(prevGray, gray)
                    sink.onClearPatches(reason)
                    invalidateSamePage()
                    sink.onPatchesMovedOrCleared()
                    sink.trace(
                        "live overlay hidden",
                        "patches cleared (reason=$reason) diff=${fmt(tracker.lastDiff)} (changing)"
                    )
                }
                sink.onStatus(DriverStatus.SCANNING)
            }

            LiveStabilityTracker.Verdict.Settling -> sink.onStatus(DriverStatus.WAITING)

            LiveStabilityTracker.Verdict.Stable -> {
                if (samePage(gray, mask)) {
                    samePageSkips++
                    sink.trace(
                        "live decision",
                        "stable; same page — skipped (diff=${fmt(tracker.lastDiff)}, source=${origin.wire})"
                    )
                    sink.onStatus(DriverStatus.SKIPPED)
                } else {
                    val clean = acquireCleanFrame()
                    if (clean == null) {
                        // Abort this cycle; same-page memory untouched so the next tick retries.
                        sink.onCleanCaptureTimeout(consecutiveCleanTimeouts)
                    } else {
                        consecutiveCleanTimeouts = 0
                        lastAttemptedThumb = gray
                        sink.onStatus(DriverStatus.TRANSLATING)
                        sink.trace(
                            "live decision",
                            "stable ${sinceChange(now)} ms; translating (diff=${fmt(tracker.lastDiff)}, frame=${clean.second})"
                        )
                        sink.onTranslate(clean.first.frame, clean.first.gray, clean.second)
                    }
                }
            }
        }

        if (origin != FrameOrigin.NEW && verdict != LiveStabilityTracker.Verdict.Stable) {
            maybeLogStarved(now, verdict)
        }

        publish(origin, verdict, now)
        return if (consecutiveCleanTimeouts >= CLEAN_CAPTURE_BACKOFF_AFTER) BACKOFF_TICK_MS else TICK_MS
    }

    // =====================================================================

    /**
     * Synchronized: the tap-capture path may take the stash from another thread while the
     * live thread is swapping frames in — a bitmap must never be recycled twice.
     */
    @Synchronized
    private fun updateStash(frame: F, gray: ByteArray, burnedIn: Boolean) {
        val old = stashFrame
        stashFrame = frame
        stashGray = gray
        stashPatchesBurnedIn = burnedIn
        if (old != null && old !== frame) guard("releaseFrame(superseded)") { source.releaseFrame(old) }
    }

    private fun samePage(gray: ByteArray, mask: BooleanArray?): Boolean {
        lastAttemptedThumb?.let {
            if (DiffMask.nearlyEqualExcluding(it, gray, mask)) return true
        }
        lastTranslatedThumb?.let {
            if (DiffMask.nearlyEqualExcluding(it, gray, mask)) return true
        }
        return false
    }

    /**
     * Obtain a frame that is guaranteed NOT to carry our own Arabic patches.
     * @return (frame + its thumbnail) to "stash" | "fresh", or null when the clean
     *         capture timed out (caller aborts the cycle and retries next tick).
     */
    private fun acquireCleanFrame(): Pair<Clean<F>, String>? {
        val stashed = stashFrame
        val stashedGray = stashGray
        if (stashed != null && stashedGray != null &&
            !stashPatchesBurnedIn && !source.patchesBurnedInNow()
        ) {
            // Clean stash: ownership moves to the translation job, the thumbnail stays.
            stashFrame = null
            return Clean(stashed, stashedGray) to "stash"
        }
        // No usable clean stash → hide the overlay and wait for the mirror to redraw.
        // Hiding our own window IS a screen change, so a real frame follows.
        if (source.patchesBurnedInNow()) {
            val hidden = guard("hideOverlay") { source.hideOverlay() } ?: false
            if (!hidden) sink.trace("clean capture", "overlay hide not confirmed — waiting for a frame anyway")
        }
        val fresh = guard("awaitNewFrame") { source.awaitNewFrame(CLEAN_CAPTURE_TIMEOUT_MS) }
        if (fresh == null) {
            cleanCaptureTimeouts++
            consecutiveCleanTimeouts++
            guard("showOverlay(timeout)") { source.showOverlay() }
            sink.trace(
                "clean capture",
                "timed out after ${CLEAN_CAPTURE_TIMEOUT_MS} ms — stash ${stashState().wire}; cycle aborted (attempt $consecutiveCleanTimeouts)"
            )
            return null
        }
        val g = guard("grayOf(clean)") { source.grayOf(fresh) }
        if (g == null) {
            guard("releaseFrame(clean)") { source.releaseFrame(fresh) }
            guard("showOverlay(clean)") { source.showOverlay() }
            return null
        }
        framesInWindow++
        framesReceived++
        lastNewFrameAtMs = clock()
        ticksWithoutNewFrame = 0L
        everSawFrame = true
        val old = stashFrame
        stashFrame = null            // ownership moves to the translation job
        stashGray = g                // captured with the overlay hidden → clean thumbnail
        stashPatchesBurnedIn = false
        if (old != null && old !== fresh) guard("releaseFrame(pre-clean)") { source.releaseFrame(old) }
        guard("showOverlay") { source.showOverlay() }
        return Clean(fresh, g) to "fresh"
    }

    private fun maybeLogStarved(now: Long, verdict: LiveStabilityTracker.Verdict) {
        if (now - lastStarvedLogMs < STARVED_LOG_INTERVAL_MS) return
        lastStarvedLogMs = now
        sink.onStarved(if (lastNewFrameAtMs <= 0L) -1L else now - lastNewFrameAtMs, verdict)
    }

    private fun sinceChange(now: Long): Long =
        if (tracker.lastChangeAtMs < 0L) -1L else now - tracker.lastChangeAtMs

    private fun rollFpsWindow(now: Long) {
        if (fpsWindowStartMs <= 0L) {
            fpsWindowStartMs = now
            framesInWindow = 0
            return
        }
        val span = now - fpsWindowStartMs
        if (span >= FPS_WINDOW_MS) {
            framesPerSecond = framesInWindow * 1000f / span
            framesInWindow = 0
            fpsWindowStartMs = now
        }
    }

    private fun publish(origin: FrameOrigin, verdict: LiveStabilityTracker.Verdict?, now: Long) {
        val d = Diagnostics(
            framesPerSecond = framesPerSecond,
            framesReceived = framesReceived,
            ticksWithoutNewFrame = ticksWithoutNewFrame,
            frameOrigin = origin,
            verdict = verdict?.let { it.javaClass.simpleName.lowercase(Locale.US) } ?: "none",
            diff = tracker.lastDiff,
            msSinceChange = sinceChange(now),
            patches = guard("patchCounts") { source.patchCounts() } ?: PatchCounts(0, 0),
            stash = stashState(),
            stashHoldsFrame = stashFrame != null,
            cleanCaptureTimeouts = cleanCaptureTimeouts,
            samePageSkips = samePageSkips
        )
        lastDiagnostics = d
        guard("onDiagnostics") { sink.onDiagnostics(d) }
    }

    private data class Clean<F>(val frame: F, val gray: ByteArray)

    companion object {
        /** ~3 fps tick. */
        const val TICK_MS = 333L

        /** Delay after repeated clean-capture timeouts (battery safety valve). */
        const val BACKOFF_TICK_MS = 1_000L
        const val CLEAN_CAPTURE_BACKOFF_AFTER = 5

        /** Stability window default (Settings slider overrides it). */
        const val DEFAULT_STABLE_WINDOW_MS = 400L
        const val DEFAULT_CHANGE_THRESHOLD = 10f

        /** Bounded wait for the mirror to redraw after the overlay is hidden. */
        const val CLEAN_CAPTURE_TIMEOUT_MS = 400L

        /** Recreate the VirtualDisplay once when no frame ever arrived this long into a session. */
        const val NO_FRAMES_RECREATE_AFTER_MS = 3_000L

        /** "live starved" is logged at most once per second. */
        const val STARVED_LOG_INTERVAL_MS = 1_000L

        private const val FPS_WINDOW_MS = 1_000L

        fun fmt(v: Float): String = String.format(Locale.US, "%.2f", v)
    }
}
