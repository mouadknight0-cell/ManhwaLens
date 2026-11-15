package com.manhwalens.app.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.manhwalens.app.ocr.geometry.Rect2D
import com.manhwalens.app.render.BubblePatch
import com.manhwalens.app.util.MainThread

/**
 * Patch-only overlay window (Part A): a SINGLE full-screen, fully transparent
 * window that draws ONLY per-bubble Arabic patches. Never draws or retains the
 * captured full frame. Touches pass straight through to the page
 * (FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE) so the reader stays scrollable.
 *
 * Lifecycle:
 *  - window attaches lazily on the first patch and stays attached while empty
 *    (transparent, invisible) to avoid re-add churn;
 *  - patches fade in over ~120 ms and out over ~80 ms (time-based);
 *  - every removal path logs `overlay removed: reason=<reason>`.
 *
 * All view/window mutations run on the main thread (see [MainThread]).
 * Patch rectangles are stored in CAPTURED-FRAME pixels; drawing scales to the
 * screen at onDraw time (capture and screen share aspect — full-screen mirror).
 */
class PatchOverlayWindow(private val context: Context, private val windowManager: WindowManager) {

    private data class Slot(
        val patch: BubblePatch,
        val startMs: Long,
        val durationMs: Long,
        val fromAlpha: Float,
        val toAlpha: Float
    ) {
        var alpha: Float = fromAlpha
    }

    private class PatchCanvasView(context: Context) : View(context) {
        val slots = LinkedHashMap<Int, Slot>()
        var arabicVisible = true
        var frameWidth = 1
        var frameHeight = 1
        /** Last removal reason + when it happened (Part D diagnostics). */
        var lastRemovalReason: String = "none"
        var lastRemovalAtMs: Long = 0L
        var lastRemovalCount: Int = 0
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(canvas: android.graphics.Canvas) {
            if (!arabicVisible) return
            val sx = width.toFloat() / frameWidth.coerceAtLeast(1)
            val sy = height.toFloat() / frameHeight.coerceAtLeast(1)
            for (slot in slots.values) {
                val a = (slot.alpha * 255f).toInt().coerceIn(0, 255)
                if (a <= 0) continue
                paint.alpha = a
                val dst = RectF(
                    slot.patch.left * sx,
                    slot.patch.top * sy,
                    slot.patch.right * sx,
                    slot.patch.bottom * sy
                )
                canvas.drawBitmap(slot.patch.bitmap, null, dst, paint)
            }
        }
    }

    private var root: PatchCanvasView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var animating = false
    private val fadeInMs = 60L
    private val fadeOutMs = 80L

    /**
     * Run [block] on the main thread and WAIT for it (150 ms cap, like the
     * hide-before-capture latch). Inline when already on main. Returns null on
     * timeout — callers must degrade gracefully (e.g. empty list / false).
     * Reading `slots` from a background thread without this is racy
     * (LinkedHashMap concurrent read during a main-thread mutation) and — worse —
     * [shiftPatchesBy] returned a stale value, silently disabling the
     * patches-ride-the-scroll path.
     */
    private inline fun <T> runOnMainSync(crossinline block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = java.util.concurrent.CountDownLatch(1)
        var out: T? = null
        Handler(Looper.getMainLooper()).post {
            try {
                out = block()
            } finally {
                latch.countDown()
            }
        }
        val ok = latch.await(150, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (!ok) android.util.Log.w(TAG, "patch overlay: main-thread block timed out after 150 ms")
        return out
    }

    /** Number of patches currently held (including ones fading out). */
    fun patchCount(): Int = runOnMainSync { root?.slots?.size ?: 0 } ?: 0

    fun hasPatches(): Boolean = runOnMainSync { root?.slots?.isNotEmpty() == true } ?: false

    /**
     * Part A (v0.9.4): true when ANY patch is on screen — fully visible OR still
     * fading out (the 80 ms tail kept by [clearImpl]). [hasPatches] reports the same
     * slots; this name states the intent for the frame stash, which must treat a
     * fading patch as "our Arabic may be burned into this frame".
     */
    fun hasVisibleOrFading(): Boolean = hasPatches()

    /** Patches at full/partial alpha that are NOT fading out. */
    fun visiblePatchCount(): Int = runOnMainSync {
        root?.slots?.values?.count { it.toAlpha > 0f } ?: 0
    } ?: 0

    /** Patches currently in the 80 ms fade-out tail. */
    fun fadingPatchCount(): Int = runOnMainSync {
        root?.slots?.values?.count { it.toAlpha == 0f } ?: 0
    } ?: 0

    /** True when the window is drawn (VISIBLE) and Arabic is switched on. */
    fun isOverlayDrawn(): Boolean = runOnMainSync {
        val v = root ?: return@runOnMainSync false
        v.visibility == View.VISIBLE && v.arabicVisible
    } ?: false

    /** `reason · N patch(es) · <ms> ago` for the debug panel (Part D). */
    fun lastRemoval(): String = runOnMainSync {
        val v = root ?: return@runOnMainSync "none"
        val age = if (v.lastRemovalAtMs <= 0L) -1L else SystemClock.elapsedRealtime() - v.lastRemovalAtMs
        "${v.lastRemovalReason} · ${v.lastRemovalCount} patch(es)" + if (age >= 0) " · ${age}ms ago" else ""
    } ?: "none"

    /** Frame-px rects of the LIVE (non-fading-out) patches — used by Live's diff masking. */
    fun rects(): List<Rect2D> = runOnMainSync {
        val v = root
        if (v == null) {
            emptyList()
        } else {
            v.slots.values.filter { it.toAlpha > 0f }.map {
                Rect2D(it.patch.left.toFloat(), it.patch.top.toFloat(), it.patch.right.toFloat(), it.patch.bottom.toFloat())
            }
        }
    } ?: emptyList()

    /** Arabic (patches) vs Original (page only) — the lens-menu toggle. */
    fun setArabicVisible(showArabic: Boolean) {
        MainThread.run {
            val v = root ?: return@run
            v.arabicVisible = showArabic
            v.invalidate()
        }
    }

    fun isArabicVisible(): Boolean = runOnMainSync { root?.arabicVisible ?: true } ?: true

    /**
     * Bring the overlay window to the front with the given patches. Replaces any
     * current patches instantly (old ones recycled). Safe from any thread.
     */
    /**
     * Bumped whenever the SET of patches is swapped or cleared (not on scroll shifts or
     * in-place bitmap swaps). A late in-place upgrade (Gemini over the ML Kit draft) checks
     * it so it never paints into a page that has since been replaced.
     */
    @Volatile
    private var contentEpochValue = 0

    fun contentEpoch(): Int = contentEpochValue

    fun replaceAll(patches: List<BubblePatch>, frameWidth: Int, frameHeight: Int) {
        MainThread.run { replaceAllImpl(patches, frameWidth, frameHeight) }
    }

    /**
     * Part D (AI cleanup): replace the bitmap of the patch with [id] IN PLACE —
     * same rect, same full alpha (no re-fade), old bitmap recycled.
     * No-op (recycling [newBitmap]) when the patch is gone.
     */
    fun replaceBitmap(id: Int, newBitmap: Bitmap) {
        MainThread.run {
            val v = root
            if (v == null) {
                runCatching { newBitmap.recycle() }
                return@run
            }
            val slot = v.slots[id]
            if (slot == null) {
                runCatching { newBitmap.recycle() }
                return@run
            }
            v.slots[id] = slot.copy(patch = slot.patch.copy(bitmap = newBitmap))
            recyclePatch(slot.patch)
            v.invalidate()
        }
    }

    /** Frame-px rect of one patch, or null when absent (used to scale the AI-cleanup result into the patch). */
    fun patchRect(id: Int): Rect2D? = runOnMainSync {
        root?.slots?.get(id)?.let {
            Rect2D(it.patch.left.toFloat(), it.patch.top.toFloat(), it.patch.right.toFloat(), it.patch.bottom.toFloat())
        }
    }

    /** Fade-in one patch (progressive painting). Replaces any patch with the same id. */
    fun upsert(patch: BubblePatch, frameWidth: Int, frameHeight: Int) {
        MainThread.run {
            val v = ensureWindow() ?: return@run
            recyclePatch(v.slots.remove(patch.id)?.patch)
            v.frameWidth = frameWidth
            v.frameHeight = frameHeight
            v.visibility = View.VISIBLE
            v.slots[patch.id] = Slot(patch, SystemClock.elapsedRealtime(), fadeInMs, 0f, 1f)
            kickAnimator()
        }
    }

    /** Instantly remove all patches and recycle their bitmaps (no fade). */
    fun beginSession(reason: String) {
        MainThread.run { clearImpl(reason, fadeOut = false) }
    }

    /** Fade out (~80 ms) the current patches; [reason] is logged as `overlay removed: reason=…`. */
    fun clearPatches(reason: String) {
        MainThread.run { clearImpl(reason, fadeOut = true) }
    }

    /**
     * Part C scroll tracking: shift every patch vertically by [dyFramePx] frame-px.
     * Positive dy = moved DOWN (camera moved up). Patches fully leaving [0, frameHeight]
     * are dropped (reason=scroll). @return true when ≥1 patch was moved.
     */
    fun shiftPatchesBy(dyFramePx: Int, frameHeight: Int): Boolean = runOnMainSync {
        val v = root
        if (v == null || dyFramePx == 0 || v.slots.isEmpty()) {
            false
        } else {
            var moved = false
            var dropped = 0
            for (slot in v.slots.values.toList()) {
                if (slot.toAlpha == 0f) continue // fading out — leave it alone
                val top = slot.patch.top + dyFramePx
                val bottom = slot.patch.bottom + dyFramePx
                if (bottom <= 0 || top >= frameHeight) {
                    v.slots.remove(slot.patch.id)
                    recyclePatch(slot.patch)
                    dropped++
                    continue
                }
                v.slots[slot.patch.id] = slot.copy(patch = slot.patch.copy(top = top, bottom = bottom))
                moved = true
            }
            if (dropped > 0) {
                v.lastRemovalReason = "scroll"
                v.lastRemovalAtMs = SystemClock.elapsedRealtime()
                v.lastRemovalCount = dropped
                android.util.Log.i(TAG, "overlay removed: reason=scroll · $dropped patch(es) moved off-screen")
            }
            v.invalidate()
            moved
        }
    } ?: false

    /** Live: draw nothing WITHOUT dropping patches (used to clear the screen for a fresh capture). */
    fun setOverlayVisible(visible: Boolean) {
        MainThread.run {
            root?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
        }
    }

    /** Tear down the window entirely (service destroy). */
    fun detach() {
        MainThread.run {
            val v = root
            root = null
            animating = false
            if (v != null) {
                val n = v.slots.size
                v.lastRemovalReason = "stop"
                v.lastRemovalAtMs = SystemClock.elapsedRealtime()
                v.lastRemovalCount = n
                android.util.Log.i(TAG, "overlay removed: reason=stop · $n patch(es)")
                for (slot in v.slots.values) recyclePatch(slot.patch)
                v.slots.clear()
                runCatching { windowManager.removeView(v) }
                    .onFailure { android.util.Log.w(TAG, "patch overlay: removeView ignored", it) }
            }
        }
    }

    // ------------------------------------------------------------------

    private fun ensureWindow(): PatchCanvasView? {
        root?.let { return it }
        val v = PatchCanvasView(context)
        v.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        return try {
            windowManager.addView(v, params)
            root = v
            android.util.Log.i(TAG, "patch overlay: transparent window attached (FLAG_NOT_TOUCHABLE | FLAG_NOT_FOCUSABLE)")
            v
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "patch overlay: addView denied (overlay permission revoked?) — ${e.message}", e)
            null
        } catch (e: WindowManager.BadTokenException) {
            android.util.Log.e(TAG, "patch overlay: addView bad token — ${e.message}", e)
            null
        } catch (e: IllegalArgumentException) {
            android.util.Log.e(TAG, "patch overlay: addView rejected — ${e.message}", e)
            null
        }
    }

    private fun replaceAllImpl(patches: List<BubblePatch>, frameWidth: Int, frameHeight: Int) {
        contentEpochValue++
        val v = ensureWindow() ?: run { patches.forEach { recyclePatch(it) }; return }
        val old = v.slots.values.toList()
        v.slots.clear()
        old.forEach { recyclePatch(it.patch) }
        v.frameWidth = frameWidth
        v.frameHeight = frameHeight
        v.visibility = View.VISIBLE
        val now = SystemClock.elapsedRealtime()
        for (p in patches) {
            v.slots[p.id] = Slot(p, now, fadeInMs, 0f, 1f)
        }
        android.util.Log.i(TAG, "patch overlay: ${patches.size} patch(es) swapped in")
        kickAnimator()
    }

    private fun clearImpl(reason: String, fadeOut: Boolean) {
        contentEpochValue++
        val v = root ?: return
        if (v.slots.isEmpty()) {
            v.lastRemovalReason = reason
            v.lastRemovalAtMs = SystemClock.elapsedRealtime()
            v.lastRemovalCount = 0
            android.util.Log.i(TAG, "overlay removed: reason=$reason · 0 patches (nothing to remove)")
            return
        }
        val n = v.slots.size
        v.lastRemovalReason = reason
        v.lastRemovalAtMs = SystemClock.elapsedRealtime()
        v.lastRemovalCount = n
        android.util.Log.i(TAG, "overlay removed: reason=$reason · $n patch(es)")
        if (!fadeOut) {
            for (slot in v.slots.values) recyclePatch(slot.patch)
            v.slots.clear()
            v.visibility = View.INVISIBLE
            v.invalidate()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val old = v.slots.values.toList()
        v.slots.clear()
        for (slot in old) {
            // Re-insert as fading-out slots measured from current alpha.
            v.slots[slot.patch.id] = Slot(slot.patch, now, fadeOutMs, slot.alpha, 0f)
        }
        kickAnimator()
    }

    private fun kickAnimator() {
        if (animating) return
        animating = true
        val tick = object : Runnable {
            override fun run() {
                val v = root
                if (v == null) {
                    animating = false
                    return
                }
                val now = SystemClock.elapsedRealtime()
                var still = false
                val fadingOut = ArrayList<Slot>()
                for (slot in v.slots.values.toList()) {
                    val t = ((now - slot.startMs).toFloat() / slot.durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
                    slot.alpha = slot.fromAlpha + (slot.toAlpha - slot.fromAlpha) * t
                    if (t < 1f) {
                        still = true
                    } else if (slot.toAlpha == 0f) {
                        fadingOut += slot
                    }
                }
                for (slot in fadingOut) {
                    v.slots.remove(slot.patch.id)
                    recyclePatch(slot.patch)
                }
                if (v.slots.isEmpty()) v.visibility = View.INVISIBLE
                v.invalidate()
                if (still) {
                    mainHandler.postDelayed(this, 16L)
                } else {
                    animating = false
                }
            }
        }
        mainHandler.post(tick)
    }

    private fun recyclePatch(patch: BubblePatch?) {
        if (patch == null) return
        runCatching { if (!patch.bitmap.isRecycled) patch.bitmap.recycle() }
    }

    companion object {
        private const val TAG = "ManhwaLens"
    }
}
