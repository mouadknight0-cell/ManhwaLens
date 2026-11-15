package com.manhwalens.app.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.manhwalens.app.ocr.geometry.Rect2D
import androidx.compose.ui.graphics.toArgb
import com.manhwalens.app.ui.theme.InkSurface
import com.manhwalens.app.ui.theme.LensViolet
import com.manhwalens.app.ui.theme.PanelAmber

/**
 * The floating lens button drawn in code: dark disc, violet ring, amber pupil.
 * Same brand mark as the launcher icon and the in-app placeholder glyph.
 */
class LensButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LensViolet.toArgb()
        alpha = 46
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = InkSurface.toArgb()
        alpha = 242
        setShadowLayer(dp(3).toFloat(), 0f, dp(1).toFloat(), 0x70000000.toInt())
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = LensViolet.toArgb()
        style = Paint.Style.STROKE
        strokeWidth = dp(4).toFloat()
    }
    private val pupilPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = PanelAmber.toArgb()
    }
    private val glintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        alpha = 220
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    companion object {
        /** Live indicator on the lens button. */
        const val LIVE_DOT_OFF = -1         // live paused / disabled — grey ring, no caption
        const val LIVE_DOT_IDLE = 0         // live on, waiting (includes "same page")
        const val LIVE_DOT_SCANNING = 1     // frame changing (scroll/animation)
        const val LIVE_DOT_TRANSLATING = 2  // OCR/translation in flight
        const val LIVE_DOT_NO_SESSION = 3   // live on but no capture session armed
        const val LIVE_DOT_ERROR = 4        // live hit an error (details in debug panel)

        /**
         * Part C ring/dot palette: grey = Live OFF or no session, green = idle/waiting,
         * amber = scanning, blue = translating, red = error.
         */
        val RING_OFF = 0xFF9E9E9E.toInt()          // grey
        val RING_IDLE = 0xFF66BB6A.toInt()         // green
        val RING_SCANNING = 0xFFFFB300.toInt()     // amber
        val RING_TRANSLATING = 0xFF42A5F5.toInt()  // blue
        val RING_ERROR = 0xFFFF5252.toInt()        // red

        /** Ring (and dot) colour for a [LIVE_DOT_*] state. */
        fun ringColorFor(state: Int): Int = when (state) {
            LIVE_DOT_SCANNING -> RING_SCANNING
            LIVE_DOT_TRANSLATING -> RING_TRANSLATING
            LIVE_DOT_NO_SESSION -> RING_OFF
            LIVE_DOT_ERROR -> RING_ERROR
            LIVE_DOT_IDLE -> RING_IDLE
            else -> RING_OFF
        }
    }

    @Volatile
    private var liveDotState: Int = LIVE_DOT_OFF

    @Volatile
    private var liveLabel: String? = null

    /** Current live dot state (for restoring the caption after a clean-capture hide). */
    val currentDotState: Int
        get() = liveDotState
    /** Current caption label (for restoring the caption after a clean-capture hide). */
    val currentCaption: String?
        get() = liveLabel

    /**
     * Live-mode indicator: colored dot + optional caption below the disc (Part B).
     * [LIVE_DOT_OFF] hides both. Caption is short plain text (service supplies
     * localized strings); empty/blank caption draws only the dot.
     */
    fun setLiveState(state: Int, label: String? = null) {
        com.manhwalens.app.util.MainThread.assertDebug("LensButtonView.setLiveState")
        if (!com.manhwalens.app.util.MainThread.isMain()) {
            // requestLayout() is main-thread-only — marshal instead of crashing.
            com.manhwalens.app.util.MainThread.run { setLiveState(state, label) }
            return
        }
        val same = liveDotState == state && liveLabel == label
        if (same) return
        liveDotState = state
        liveLabel = if (state == LIVE_DOT_OFF) null else label
        requestLayout()
        postInvalidate()
    }

    init {
        // Software layer so setShadowLayer actually renders on API 26.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    private val sizePx: Int by lazy { dp(56) }
    private val captionPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 11f * resources.displayMetrics.scaledDensity
        }
    }
    private val captionBgPaint by lazy { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC14141D.toInt() } }
    private val captionRect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val label = if (liveDotState == LIVE_DOT_OFF) null else liveLabel
        val captionH = if (label.isNullOrBlank()) 0 else dp(20)
        setMeasuredDimension(sizePx, sizePx + captionH)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f

        // Soft glow behind everything (tinted with the current live state)
        glowPaint.color = ringColorFor(liveDotState)
        canvas.drawCircle(cx, cy, r * 0.98f, glowPaint)
        // Dark disc with shadow
        canvas.drawCircle(cx, cy, r * 0.86f, discPaint)
        // Ring colour = live state (Part C): grey OFF/no-session, green idle, amber
        // scanning, blue translating, red error.
        val ringRadius = r * 0.52f
        ringPaint.color = ringColorFor(liveDotState)
        canvas.drawCircle(cx, cy, ringRadius, ringPaint)
        // Amber pupil
        canvas.drawCircle(cx, cy, r * 0.21f, pupilPaint)
        // Glint
        canvas.drawCircle(cx - r * 0.14f, cy - r * 0.15f, r * 0.06f, glintPaint)

        // Live-mode indicator dot + caption (Part B): idle=green, scanning=amber,
        // translating=violet, no-session=grey, error=red
        val dot = liveDotState
        if (dot != LIVE_DOT_OFF) {
            val color = ringColorFor(dot)
            val dotR = dp(7).toFloat()
            val dotX = width - dotR - dp(2).toFloat()
            val dotY = sizePx - dotR - dp(2).toFloat()
            dotPaint.color = 0xFF0E0E14.toInt() // dark rim for contrast
            canvas.drawCircle(dotX, dotY, dotR + dp(2).toFloat(), dotPaint)
            dotPaint.color = color
            canvas.drawCircle(dotX, dotY, dotR, dotPaint)

            val label = liveLabel
            if (!label.isNullOrBlank()) {
                val textW = captionPaint.measureText(label).coerceAtMost(width - dp(8).toFloat())
                val top = sizePx.toFloat()
                captionRect.set((width - textW) / 2f - dp(6), top + dp(1), (width + textW) / 2f + dp(6), top + dp(18))
                canvas.drawRoundRect(captionRect, dp(9).toFloat(), dp(9).toFloat(), captionBgPaint)
                captionPaint.color = 0xFFE8E6F5.toInt()
                canvas.drawText(label, width / 2f, top + dp(15), captionPaint)
            }
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    /** The button's on-screen rect in FRAME (captured-bitmap) pixels, or null when not laid out. */
    fun screenRect(): Rect2D? {
        if (width <= 0 || height <= 0) return null
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        return Rect2D(
            loc[0].toFloat(), loc[1].toFloat(),
            (loc[0] + width).toFloat(), (loc[1] + height).toFloat()
        )
    }

    /** The live caption's on-screen rect in FRAME pixels, or null when no caption is shown. */
    fun captionRect(): Rect2D? {
        if (liveLabel.isNullOrBlank() || width <= 0 || height <= 0) return null
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val textW = captionPaint.measureText(liveLabel!!).coerceAtMost(width - dp(8).toFloat())
        val top = sizePx.toFloat()
        return Rect2D(
            (width - textW) / 2f - dp(6) + loc[0],
            top + dp(1) + loc[1],
            (width + textW) / 2f + dp(6) + loc[0],
            top + dp(18) + loc[1]
        )
    }
}
