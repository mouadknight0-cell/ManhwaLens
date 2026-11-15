package com.manhwalens.app.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import com.manhwalens.app.R
import com.manhwalens.app.ocr.geometry.Rect2D
import com.manhwalens.app.util.MainThread

/**
 * The lens menu (v0.9.4 · Part C): a compact, touchable popup anchored NEXT TO the
 * floating lens, opened by a single tap. It is the visible ON/OFF surface that
 * v0.9.3 lacked (back then the menu only opened on a ≥450 ms long press, which now
 * toggles Live directly instead).
 *
 * Rows (large touch targets, localized EN + AR):
 *   · header              — current state ("Live ON" / "Live OFF") + close ✕
 *   · Live mode switch    — green when on
 *   · Translate now       — one-shot capture; works with Live OFF
 *   · Arabic / Original   — show the translated patches or the original page
 *   · Clear translation   — removes the Arabic patches (reason=user clear)
 *   · Review last capture — full-frame viewer (tap captures only)
 *   · Stop ManhwaLens     — stops the loop, releases the session, removes every overlay
 *   · Settings…           — opens the app's settings screen
 *
 * Behaviour:
 *   · auto-closes after [AUTO_CLOSE_MS] of inactivity (any touch inside resets the timer);
 *   · a tap OUTSIDE closes it (`FLAG_WATCH_OUTSIDE_TOUCH`) while still reaching the reader;
 *   · clamped to the screen, flipped to the other side of the lens when it would overflow;
 *   · never focusable — the manhwa reader keeps focus, no IME.
 *
 * [screenRect] is published as a volatile cached rect so the live loop can exclude the
 * menu from the frame-diff mask without touching views off the main thread.
 */
class LensMenuWindow(private val context: Context, private val windowManager: WindowManager) {

    interface Callbacks {
        fun onToggleLive(enable: Boolean)
        fun onTranslateNow()
        fun onToggleArabic(showArabic: Boolean)
        fun onClearPatches()
        fun onReviewCapture()
        fun onStopEverything()
        fun onOpenSettings()
    }

    /** Where the lens button currently is (screen px) — the menu anchors next to it. */
    data class Anchor(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val screenWidth: Int,
        val screenHeight: Int
    )

    private var root: MenuRoot? = null
    private var params: WindowManager.LayoutParams? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var liveIcon: MenuIconView? = null
    private var liveLabel: TextView? = null

    /** True while Arabic patches are on screen — decides whether the Clear icon is offered. */
    var patchesShowing: (() -> Boolean)? = null
    private var arabicSwitch: android.widget.Switch? = null
    private var headerState: TextView? = null
    private var statusLine: TextView? = null
    private var callbacks: Callbacks? = null

    /**
     * v0.9.5 Part 2: where the "Live status" line gets its text from — the service's
     * `liveStatusLine()` (gate + verdict + stash). Kept as a provider so the menu can
     * refresh itself on a 1 s beat while it is open, and stays test-free of Android.
     */
    private var liveStatusProvider: (() -> String)? = null
    private val statusRefresher = object : Runnable {
        override fun run() {
            if (root == null) return
            refreshLiveStatus()
            mainHandler.postDelayed(this, STATUS_REFRESH_MS)
        }
    }

    /**
     * Every DECISION (open/close, 4 s auto-close, what a row does) lives in the pure
     * [LensMenuModel]; this class is only the view. The model is what the unit tests
     * exercise — see LensMenuModelTest.
     */
    val model = LensMenuModel(AUTO_CLOSE_MS) { SystemClock.elapsedRealtime() }

    /** Screen-px rect of the menu window, or null when it is not showing. Read from any thread. */
    @Volatile
    private var cachedRect: Rect2D? = null

    /** elapsedRealtime of the last teardown + where the menu was — the capture path uses both. */
    @Volatile
    var lastHiddenAtMs: Long = 0L
        private set

    @Volatile
    var lastHiddenRect: Rect2D? = null
        private set

    private val autoClose = Runnable {
        val events = model.tick()
        if (events.isEmpty()) {
            scheduleAutoClose()
        } else {
            android.util.Log.i(TAG, "lens menu: auto-closed after ${AUTO_CLOSE_MS}ms of inactivity")
            runEvents(events)
        }
    }

    fun isShowing(): Boolean = root != null

    fun screenRect(): Rect2D? = cachedRect

    /** Part D: why the menu closed last + how many times it auto-closed. */
    fun diagnosticsLine(): String =
        "menu: open=${model.isOpen} lastClose=${model.lastCloseReason.wire} autoCloses=${model.autoCloseCount}"

    /** Model events → view teardown + service callbacks. */
    private fun runEvents(events: List<LensMenuModel.Event>) {
        // v0.9.12: Capture / Clear / Exit emit [Hidden, <action>]. Hidden runs teardown(), which
        // forgets the callbacks — so the action that followed was silently dropped and those
        // three icons did nothing. Hold the callbacks for the whole batch.
        val callbacks = this.callbacks
        for (event in events) {
            when (event) {
                LensMenuModel.Event.Shown -> Unit // the view is added by show() itself
                LensMenuModel.Event.Hidden -> teardown()
                is LensMenuModel.Event.LiveToggled -> callbacks?.onToggleLive(event.enable)
                LensMenuModel.Event.TranslateNow -> callbacks?.onTranslateNow()
                is LensMenuModel.Event.ArabicToggled -> callbacks?.onToggleArabic(event.showArabic)
                LensMenuModel.Event.ClearTranslation -> callbacks?.onClearPatches()
                LensMenuModel.Event.ReviewCapture -> callbacks?.onReviewCapture()
                LensMenuModel.Event.StopEverything -> callbacks?.onStopEverything()
                LensMenuModel.Event.OpenSettings -> callbacks?.onOpenSettings()
            }
        }
    }

    /** Open (or close, when already open) the menu next to [anchor]. */
    @JvmOverloads
    fun toggle(
        liveOn: Boolean,
        patchesOn: Boolean,
        reviewAvailable: Boolean,
        anchor: Anchor,
        callbacks: Callbacks,
        liveStatus: (() -> String)? = null
    ) {
        MainThread.run {
            if (model.isOpen || root != null) {
                dismiss()
                return@run
            }
            this.callbacks = callbacks
            liveStatusProvider = liveStatus
            show(liveOn, patchesOn, reviewAvailable, anchor)
        }
    }

    /** v0.9.5 Part 2: repaint the "Live status" line (safe to call from any thread). */
    fun refreshLiveStatus() {
        MainThread.run {
            val tv = statusLine ?: return@run
            val text = runCatching { liveStatusProvider?.invoke() }.getOrNull()
                ?: context.getString(R.string.lens_menu_status_unknown)
            tv.text = text
            // Green when the loop is running, amber while a gate waits, grey otherwise.
            tv.setTextColor(
                when {
                    text.contains("gate=none") -> 0xFF66BB6A.toInt()
                    text.contains("BLOCKED") || text.contains("gate=projection") -> 0xFFFF6E6E.toInt()
                    text.contains("gate=") -> 0xFFFFC45E.toInt()
                    else -> 0xFF9B8CFF.toInt()
                }
            )
        }
    }

    /** Refresh the header + switches in place (called after a Live toggle). */
    fun updateState(liveOn: Boolean, patchesOn: Boolean) {
        MainThread.run {
            if (root == null) return@run
            model.sync(liveOn, patchesOn)
            liveIcon?.kind = if (liveOn) MenuIconView.Kind.STOP else MenuIconView.Kind.PLAY
            liveIcon?.tint = if (liveOn) COLOR_RED else COLOR_GREEN
            liveLabel?.text = context.getString(
                if (liveOn) R.string.lens_icon_live_stop else R.string.lens_icon_live_start
            )
            arabicSwitch?.let { sw ->
                sw.setOnCheckedChangeListener(null)
                sw.isChecked = patchesOn
                sw.setOnCheckedChangeListener { _, on -> callbacks?.onToggleArabic(on) }
            }
            renderHeader(liveOn)
        }
    }

    /** Close the menu (service-side request: drag, capture start, stop, outside tap…). */
    @JvmOverloads
    fun dismiss(reason: LensMenuModel.CloseReason = LensMenuModel.CloseReason.ROW_ACTION) {
        MainThread.run {
            if (model.isOpen) {
                runEvents(model.close(reason))
            } else {
                teardown()
            }
        }
    }

    private fun teardown() {
        mainHandler.removeCallbacks(autoClose)
        val r = root ?: return
        root = null
        params = null
        mainHandler.removeCallbacks(statusRefresher)
        liveIcon = null
        liveLabel = null
        arabicSwitch = null
        headerState = null
        statusLine = null
        callbacks = null
        lastHiddenRect = cachedRect
        lastHiddenAtMs = SystemClock.elapsedRealtime()
        cachedRect = null
        runCatching { windowManager.removeView(r) }
            .onFailure { android.util.Log.w(TAG, "lens menu: removeView ignored", it) }
    }

    // ------------------------------------------------------------------

    /** Root view: catches ACTION_OUTSIDE (tap outside → close) and resets the auto-close timer. */
    private inner class MenuRoot(context: Context) : FrameLayout(context) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                android.util.Log.i(TAG, "lens menu: tap outside — closing")
                runEvents(model.onOutsideTap())
                return true
            }
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                // Any touch inside the menu resets the 4 s inactivity timer.
                model.touch()
                scheduleAutoClose()
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    private fun scheduleAutoClose() {
        mainHandler.removeCallbacks(autoClose)
        val delay = model.msUntilAutoClose().let { if (it <= 0L) AUTO_CLOSE_MS else it }
        mainHandler.postDelayed(autoClose, delay)
    }

    private fun show(
        liveOn: Boolean,
        patchesOn: Boolean,
        reviewAvailable: Boolean,
        anchor: Anchor
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        // v0.9.8: the menu is a compact strip of round ICON buttons attached to the lens
        // (Live on/off · Capture · Clear [only while patches are up] · Exit) instead of a
        // text card. Small footprint = it hides almost nothing of the page.
        val root = MenuRoot(context)
        val strip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xE61B1B26.toInt())
                cornerRadius = dp(36).toFloat()
                setStroke(dp(1), 0x33FFFFFF)
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
        }

        val items = ArrayList<View>(4)

        // ---- Live ON/OFF (play when off, stop when on) ----
        val live = iconButton(
            ::dp,
            if (liveOn) MenuIconView.Kind.STOP else MenuIconView.Kind.PLAY,
            if (liveOn) COLOR_RED else COLOR_GREEN,
            context.getString(if (liveOn) R.string.lens_icon_live_stop else R.string.lens_icon_live_start)
        ) {
            val enable = !model.liveOn
            runEvents(model.onLiveSwitch(enable))
            // Close right away: the menu must not sit on the page while Live starts scanning.
            dismiss()
        }
        liveIcon = live.icon
        liveLabel = live.label
        items += live.view

        // ---- Capture (one-shot translate of the current screen) ----
        items += iconButton(::dp, MenuIconView.Kind.CAPTURE, COLOR_ACCENT, context.getString(R.string.lens_icon_capture)) {
            runEvents(model.onTranslateNow())
        }.view

        // ---- Clear translation (only useful while Arabic patches are on screen) ----
        if (patchesShowing?.invoke() == true) {
            items += iconButton(::dp, MenuIconView.Kind.CLEAR, COLOR_NEUTRAL, context.getString(R.string.lens_icon_clear)) {
                runEvents(model.onClearTranslation())
            }.view
        }

        // ---- Exit (stop everything, remove every overlay) ----
        items += iconButton(::dp, MenuIconView.Kind.EXIT, COLOR_RED, context.getString(R.string.lens_icon_exit)) {
            runEvents(model.onStopEverything())
        }.view

        items.forEach { strip.addView(it) }
        root.addView(strip)

        val estimatedW = dp(12 + ITEM_WIDTH_DP * items.size)
        val estimatedH = dp(MENU_ESTIMATED_HEIGHT_DP)

        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Touchable (unlike the patch overlay) but never focusable, and outside taps
            // still reach the reader while we get an ACTION_OUTSIDE notification.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val pos = clampPosition(anchor, estimatedW, estimatedH)
            x = pos.first
            y = pos.second
        }

        try {
            windowManager.addView(root, p)
            this.root = root
            this.params = p
            publishRect(p, root)
            // Re-clamp once the real size is known (WRAP_CONTENT is measured at layout time).
            root.post {
                val rp = params ?: return@post
                val rr = this.root ?: return@post
                val pos = clampPosition(anchor, rr.width.coerceAtLeast(1), rr.height.coerceAtLeast(1))
                if (pos.first != rp.x || pos.second != rp.y) {
                    rp.x = pos.first
                    rp.y = pos.second
                    runCatching { windowManager.updateViewLayout(rr, rp) }
                }
                publishRect(rp, rr)
            }
            // Pop the icons out of the lens one after another.
            items.forEachIndexed { i, v ->
                v.alpha = 0f
                v.scaleX = 0.4f
                v.scaleY = 0.4f
                v.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setStartDelay(i * 35L).setDuration(140L).start()
            }
            runEvents(model.open(liveOn, patchesOn, reviewAvailable))
            scheduleAutoClose()
            android.util.Log.i(TAG, "lens menu: icon strip shown at (${p.x},${p.y}) anchored to lens (${anchor.x},${anchor.y})")
        } catch (e: SecurityException) {
            android.util.Log.e(TAG, "lens menu: addView denied — ${e.message}", e)
        } catch (e: WindowManager.BadTokenException) {
            android.util.Log.e(TAG, "lens menu: addView bad token — ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            android.util.Log.e(TAG, "lens menu: addView rejected — ${e.message}", e)
        }
    }

    private class IconButton(val view: View, val icon: MenuIconView, val label: TextView)

    /** One round icon button with a tiny caption under it. */
    private fun iconButton(
        dp: (Int) -> Int,
        kind: MenuIconView.Kind,
        color: Int,
        caption: String,
        onClick: () -> Unit
    ): IconButton {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(dp(ITEM_WIDTH_DP), LinearLayout.LayoutParams.WRAP_CONTENT)
            isClickable = true
            contentDescription = caption
            setOnClickListener { onClick() }
        }
        val icon = MenuIconView(context).apply {
            this.kind = kind
            this.tint = color
            layoutParams = LinearLayout.LayoutParams(dp(46), dp(46))
        }
        val label = TextView(context).apply {
            text = caption
            setTextColor(0xFFE8E6F2.toInt())
            textSize = 10f
            maxLines = 1
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }
        column.addView(icon)
        column.addView(label)
        return IconButton(column, icon, label)
    }

    /** Icons are drawn on Canvas — no bitmaps, no font glyphs, crisp at every density. */
    private class MenuIconView(context: Context) : View(context) {
        enum class Kind { PLAY, STOP, CAPTURE, CLEAR, EXIT }

        var kind: Kind = Kind.PLAY
            set(value) { field = value; invalidate() }
        var tint: Int = 0xFFFFFFFF.toInt()
            set(value) { field = value; invalidate() }

        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        private val path = android.graphics.Path()

        override fun onDraw(canvas: android.graphics.Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val cx = w / 2f
            val cy = h / 2f
            val r = minOf(w, h) / 2f
            // Disc background, lightly tinted with the action colour.
            fill.style = android.graphics.Paint.Style.FILL
            fill.color = (tint and 0x00FFFFFF) or 0x33000000
            canvas.drawCircle(cx, cy, r, fill)
            stroke.color = tint
            stroke.strokeWidth = r * 0.07f
            canvas.drawCircle(cx, cy, r - stroke.strokeWidth, stroke)

            fill.color = tint
            stroke.strokeWidth = r * 0.16f
            val u = r * 0.42f
            when (kind) {
                Kind.PLAY -> {
                    path.reset()
                    path.moveTo(cx - u * 0.7f, cy - u)
                    path.lineTo(cx + u, cy)
                    path.lineTo(cx - u * 0.7f, cy + u)
                    path.close()
                    canvas.drawPath(path, fill)
                }
                Kind.STOP -> {
                    canvas.drawRoundRect(cx - u * 0.85f, cy - u * 0.85f, cx + u * 0.85f, cy + u * 0.85f, u * 0.2f, u * 0.2f, fill)
                }
                Kind.CAPTURE -> {
                    // Viewfinder corners + shutter dot.
                    val c = u * 1.05f
                    val l = u * 0.55f
                    path.reset()
                    path.moveTo(cx - c, cy - c + l); path.lineTo(cx - c, cy - c); path.lineTo(cx - c + l, cy - c)
                    path.moveTo(cx + c - l, cy - c); path.lineTo(cx + c, cy - c); path.lineTo(cx + c, cy - c + l)
                    path.moveTo(cx + c, cy + c - l); path.lineTo(cx + c, cy + c); path.lineTo(cx + c - l, cy + c)
                    path.moveTo(cx - c + l, cy + c); path.lineTo(cx - c, cy + c); path.lineTo(cx - c, cy + c - l)
                    canvas.drawPath(path, stroke)
                    canvas.drawCircle(cx, cy, u * 0.42f, fill)
                }
                Kind.CLEAR -> {
                    canvas.drawLine(cx - u * 0.8f, cy - u * 0.8f, cx + u * 0.8f, cy + u * 0.8f, stroke)
                    canvas.drawLine(cx + u * 0.8f, cy - u * 0.8f, cx - u * 0.8f, cy + u * 0.8f, stroke)
                }
                Kind.EXIT -> {
                    // Power symbol: open ring + vertical bar.
                    val rr = u * 0.95f
                    canvas.drawArc(cx - rr, cy - rr + u * 0.1f, cx + rr, cy + rr + u * 0.1f, -55f, 290f, false, stroke)
                    canvas.drawLine(cx, cy - u * 1.05f, cx, cy - u * 0.05f, stroke)
                }
            }
        }
    }

    private fun publishRect(p: WindowManager.LayoutParams, view: View) {
        val w = view.width.takeIf { it > 0 } ?: p.width
        val h = view.height.takeIf { it > 0 } ?: 0
        cachedRect = Rect2D(
            p.x.toFloat(),
            p.y.toFloat(),
            (p.x + w).toFloat(),
            (p.y + h).toFloat()
        )
    }

    /** Place the menu beside the lens, flipped + clamped so it always fits on screen. */
    private fun clampPosition(anchor: Anchor, menuW: Int, menuH: Int): Pair<Int, Int> {
        val gap = (8 * context.resources.displayMetrics.density).toInt()
        val lensRight = anchor.x + anchor.width
        val fitsRight = lensRight + gap + menuW <= anchor.screenWidth
        val fitsLeft = anchor.x - gap - menuW >= 0
        val x = when {
            // Prefer the side with more room (the lens snaps to an edge, so usually one fits).
            lensRight <= anchor.screenWidth / 2 && fitsRight -> lensRight + gap
            fitsLeft -> anchor.x - gap - menuW
            fitsRight -> lensRight + gap
            else -> (anchor.screenWidth - menuW).coerceAtLeast(0)
        }
        val y = (anchor.y + (anchor.height - menuH) / 2).coerceIn(0, (anchor.screenHeight - menuH).coerceAtLeast(0))
        return x.coerceIn(0, (anchor.screenWidth - menuW).coerceAtLeast(0)) to y
    }

    private fun renderHeader(liveOn: Boolean) {
        val tv = headerState ?: return
        tv.text = context.getString(if (liveOn) R.string.lens_menu_state_on else R.string.lens_menu_state_off)
        tv.setTextColor(if (liveOn) 0xFF66BB6A.toInt() else 0xFF9E9E9E.toInt())
    }

    private fun divider(parent: LinearLayout, dp: (Int) -> Int) {
        parent.addView(View(context).apply {
            setBackgroundColor(0x22FFFFFF)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        })
    }

    private fun switchRow(
        parent: LinearLayout,
        dp: (Int) -> Int,
        labelRes: Int,
        checked: Boolean,
        big: Boolean = false,
        onChange: (Boolean) -> Unit
    ): Switch {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(if (big) 56 else 48)
            setPadding(0, dp(6), 0, dp(6))
            isClickable = true
            if (big) {
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x14FFFFFF)
                    cornerRadius = dp(12).toFloat()
                }
                setPadding(dp(10), dp(6), dp(10), dp(6))
            }
        }
        val label = TextView(context).apply {
            text = context.getString(labelRes)
            setTextColor(0xFFE8E6F2.toInt())
            textSize = if (big) 16f else 14f
            if (big) setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val toggle = Switch(context).apply {
            isChecked = checked
            if (big) {
                // Green track when Live is ON (Part C).
                thumbTintList = android.content.res.ColorStateList.valueOf(
                    if (checked) 0xFF66BB6A.toInt() else 0xFFBDBDBD.toInt()
                )
                trackTintList = android.content.res.ColorStateList.valueOf(
                    if (checked) 0x6666BB6A else 0x33FFFFFF
                )
            }
            setOnCheckedChangeListener { _: android.widget.CompoundButton, on: Boolean ->
                if (big) {
                    thumbTintList = android.content.res.ColorStateList.valueOf(
                        if (on) 0xFF66BB6A.toInt() else 0xFFBDBDBD.toInt()
                    )
                    trackTintList = android.content.res.ColorStateList.valueOf(
                        if (on) 0x6666BB6A else 0x33FFFFFF
                    )
                }
                onChange(on)
            }
        }
        row.setOnClickListener { toggle.toggle() }
        row.addView(label)
        row.addView(toggle)
        parent.addView(row)
        return toggle
    }

    private fun plainRow(
        parent: LinearLayout,
        dp: (Int) -> Int,
        labelRes: Int,
        enabled: Boolean = true,
        danger: Boolean = false,
        onClick: () -> Unit
    ) {
        val row = TextView(context).apply {
            text = context.getString(labelRes)
            setTextColor(
                when {
                    !enabled -> 0xFF5C5A6E.toInt()
                    danger -> 0xFFFF8A80.toInt()
                    else -> 0xFFE8E6F2.toInt()
                }
            )
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(48)
            setPadding(0, dp(10), 0, dp(10))
            isClickable = enabled
            if (enabled) setOnClickListener { onClick() }
        }
        parent.addView(row)
    }

    companion object {
        private const val TAG = "ManhwaLens"

        /** Part C: the menu closes itself after this much inactivity. */
        const val AUTO_CLOSE_MS = 4_000L

        /** Height estimate used for the first clamp (the real one follows on layout). */
        private const val MENU_ESTIMATED_HEIGHT_DP = 78

        /** Width of one icon column in the strip. */
        private const val ITEM_WIDTH_DP = 58

        private const val COLOR_GREEN = 0xFF66BB6A.toInt()
        private const val COLOR_RED = 0xFFFF6E6E.toInt()
        private const val COLOR_ACCENT = 0xFF9B8CFF.toInt()
        private const val COLOR_NEUTRAL = 0xFFE8E6F2.toInt()

        /** v0.9.5 Part 2: the Live status line repaints once per second while the menu is open. */
        private const val STATUS_REFRESH_MS = 1_000L
    }
}
