package com.manhwalens.app.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import com.manhwalens.app.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Rasterizes one Arabic patch for a bubble (Part B).
 *
 * Strategy per bubble:
 *  1. text rect = block bounds (union of OCR line boxes) + [PAD_PX] pad — the base patch;
 *  2. try to EXPAND into the bubble interior with a bounded flood fill over pixels
 *     similar to the bubble background, stopping at dark outline strokes and never
 *     beyond 1.4× the text box ([PatchMath.expansionBound]);
 *  3. sample the ring around the TEXT area (excluding glyphs):
 *       · uniform ring → fill the patch rect with the median ring colour
 *         (rounded rect, 2 px feather);
 *       · non-uniform ring (gradient/art) → copy the crop, build a text mask
 *         (pixels differing from the dominant colour ∪ dark strokes, dilated 2 px)
 *         and inpaint it with iterative neighbour averaging;
 *  4. draw the Arabic text (Cairo, RTL, auto-fit, ≤4 lines) on top — with a thin
 *     outline on non-uniform/mid-luma backgrounds;
 *  5. return a SMALL bitmap covering only the patch rect — never the full frame.
 */
object PatchSurface {

    private const val TAG = "ManhwaLens"
    private const val PAD_PX = 5               // pad around the OCR block bounds (4–6 px spec)
    private const val INNER_PAD_PX = 6         // text breathing room inside the patch
    private const val FEATHER_PX = 2f          // soft edge width
    private const val MIN_TEXT_PX = 9f         // never go below this readable size
    private const val MIN_PATCH_PX = 12        // degenerate guard
    private const val RING_WIDTH = 4
    private const val FLOOD_DELTA = 26
    private const val OUTLINE_LUMA = 80
    private const val MAX_AXIS_SCALE = 1.4f

    private var cairoTypeface: Typeface? = null

    private fun typeface(context: Context): Typeface =
        cairoTypeface ?: (ResourcesCompat.getFont(context, R.font.cairo_bold)
            ?: ResourcesCompat.getFont(context, R.font.cairo)
            ?: Typeface.DEFAULT).also { cairoTypeface = it }

    /** Build the patch for [box] sampled from [source]. Returns null on unusable input. */
    fun build(context: Context, source: Bitmap, box: BubbleRenderBox): BubblePatch? {
        if (source.isRecycled || box.arabic.isBlank()) return null
        val frameW = source.width
        val frameH = source.height

        // ---------- 1) base text rect ----------
        val inner = PatchMath.unionWithPad(
            listOf(
                com.manhwalens.app.ocr.geometry.Rect2D(
                    box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat()
                )
            ),
            PAD_PX.toFloat(), frameW, frameH
        ) ?: return null

        // ---------- 2) bubble-interior expansion (bounded flood fill) ----------
        val bound = PatchMath.expansionBound(inner, frameW, frameH, MAX_AXIS_SCALE)
        val patchRect = tryExpandToBubble(source, inner, bound, box.backgroundColor)
        val left = patchRect.left.toInt()
        val top = patchRect.top.toInt()
        val right = patchRect.right.toInt().coerceAtMost(frameW)
        val bottom = patchRect.bottom.toInt().coerceAtMost(frameH)
        val w = right - left
        val h = bottom - top
        if (w < MIN_PATCH_PX || h < MIN_PATCH_PX) return null

        // ---------- 3) crop pixels + ring classification ----------
        val crop = IntArray(w * h)
        return try {
            source.getPixels(crop, 0, w, left, top, w, h)
            buildFromCrop(context, crop, w, h, left, top, box, inner, frameW, frameH)
        } catch (oom: OutOfMemoryError) {
            Log.e(TAG, "patch #${box.id}: crop allocation OOM — bubble skipped", oom)
            null
        } catch (e: Exception) {
            Log.e(TAG, "patch #${box.id}: build failed — ${e.javaClass.name}: ${e.message}", e)
            null
        }
    }

    // ------------------------------------------------------------------

    /** Flood fill from the text-rect perimeter over background-like pixels; returns the grown rect (or null on starvation). */
    private fun tryExpandToBubble(
        source: Bitmap,
        inner: com.manhwalens.app.ocr.geometry.Rect2D,
        bound: com.manhwalens.app.ocr.geometry.Rect2D,
        refColor: Int
    ): com.manhwalens.app.ocr.geometry.Rect2D {
        val bl = bound.left.toInt().coerceIn(0, source.width - 1)
        val bt = bound.top.toInt().coerceIn(0, source.height - 1)
        val br = bound.right.toInt().coerceIn(bl + 1, source.width)
        val bb = bound.bottom.toInt().coerceIn(bt + 1, source.height)
        val cw = br - bl
        val ch = bb - bt
        return try {
            val crop = IntArray(cw * ch)
            source.getPixels(crop, 0, cw, bl, bt, cw, ch)
            val localInner = com.manhwalens.app.ocr.geometry.Rect2D(
                inner.left - bl, inner.top - bt, inner.right - bl, inner.bottom - bt
            )
            val localBound = com.manhwalens.app.ocr.geometry.Rect2D(0f, 0f, cw.toFloat(), ch.toFloat())
            val reached = PatchMath.floodRegion(crop, cw, ch, localInner, refColor, localBound, delta = FLOOD_DELTA, outlineLuma = OUTLINE_LUMA)
            if (reached == null) {
                // No flood growth (text touches unresolvable edges) — stay with the padded text rect.
                inner
            } else {
                // Union of the flood-reached area and the padded text rect, clamped to the frame.
                com.manhwalens.app.ocr.geometry.Rect2D(
                    max(reached.left + bl, 0f),
                    max(reached.top + bt, 0f),
                    min(reached.right + bl, source.width.toFloat()),
                    min(reached.bottom + bt, source.height.toFloat())
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "flood expansion failed (${e.javaClass.name}) — using padded text rect")
            inner
        }
    }

    private fun buildFromCrop(
        context: Context,
        crop: IntArray,
        w: Int,
        h: Int,
        frameLeft: Int,
        frameTop: Int,
        box: BubbleRenderBox,
        inner: com.manhwalens.app.ocr.geometry.Rect2D,
        frameW: Int,
        frameH: Int
    ): BubblePatch {
        // Ring around the TEXT rect, in crop-local coords (excludes glyph pixels).
        val localInner = com.manhwalens.app.ocr.geometry.Rect2D(
            (inner.left - frameLeft).coerceAtLeast(0f),
            (inner.top - frameTop).coerceAtLeast(0f),
            (inner.right - frameLeft).coerceAtMost(w.toFloat()),
            (inner.bottom - frameTop).coerceAtMost(h.toFloat())
        )
        val ring = PatchMath.sampleRing(crop, w, h, localInner, RING_WIDTH)
        val uniformRing = ring?.uniform != false // ring unreadable → fall back to solid fill
        val bgColor: Int = if (uniformRing) {
            ring?.color ?: (0xFF000000.toInt() or (0xFFFFFF and box.backgroundColor))
        } else {
            // Dominant interior colour stands in for the gradient (fill fallback if inpaint degrades)
            ColorSampler.sample(crop, w, h, 0, 0, w, h) ?: box.backgroundColor
        }

        // ---------- fill the patch background ----------
        val patch = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(patch)
        if (uniformRing) {
            paintSolidFeathered(canvas, w, h, bgColor)
        } else {
            // Non-uniform ring (gradient / art): keep the crop, erase glyphs by mask+inpaint.
            val work = crop.copyOf()
            val dominant = ColorSampler.sample(work, w, h, 0, 0, w, h) ?: box.backgroundColor
            val mask = PatchMath.buildTextMask(work, w, h, dominant, delta = 42, strokeLuma = 110, dilate = 2)
            PatchMath.inpaintMasked(work, w, h, mask)
            // Feather the crop edges into transparency so the patch blends into the art.
            val cropBmp = Bitmap.createBitmap(work, 0, w, w, h, Bitmap.Config.ARGB_8888)
            canvas.drawBitmap(cropBmp, 0f, 0f, null)
            applyEdgeFeather(canvas, w, h)
            cropBmp.recycle()
        }

        // ---------- Arabic text ----------
        val textBg = if (uniformRing) bgColor else (PatchMath.averageColor(crop, w, h, localInner) ?: bgColor)
        val textColor = ColorSampler.bestTextColorOn(textBg)
        val complexBg = !uniformRing || needsOutline(textBg)
        drawArabicText(context, canvas, box.arabic, w, h, textColor, complexBg, box.id)

        return BubblePatch(
            id = box.id,
            left = frameLeft,
            top = frameTop,
            right = frameLeft + w,
            bottom = frameTop + h,
            bitmap = patch
        )
    }

    private fun paintSolidFeathered(canvas: Canvas, w: Int, h: Int, bg: Int) {
        val outer = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val corner = min(18f, min(w, h) * 0.22f)
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = bg
            maskFilter = BlurMaskFilter(FEATHER_PX, BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(outer, corner, corner, bgPaint)
        val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = bg
        }
        val inset = RectF(FEATHER_PX, FEATHER_PX, w - FEATHER_PX, h - FEATHER_PX)
        canvas.drawRoundRect(inset, corner - FEATHER_PX, corner - FEATHER_PX, corePaint)
    }

    /** Soft-edge the pasted crop: destination-in a blurred rounded-rect so the patch edges blend into the art. */
    private fun applyEdgeFeather(canvas: Canvas, w: Int, h: Int) {
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val mc = Canvas(mask)
        val corner = min(18f, min(w, h) * 0.22f)
        val mp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFFFFFFFF.toInt()
            maskFilter = BlurMaskFilter(FEATHER_PX, BlurMaskFilter.Blur.NORMAL)
        }
        mc.drawRoundRect(RectF(1f, 1f, w - 1f, h - 1f), corner, corner, mp)
        val dstIn = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        }
        canvas.drawBitmap(mask, 0f, 0f, dstIn)
        mask.recycle()
    }

    private fun drawArabicText(
        context: Context,
        canvas: Canvas,
        arabic: String,
        w: Int,
        h: Int,
        textColor: Int,
        complexBg: Boolean,
        boxId: Int
    ) {
        val innerW = (w - INNER_PAD_PX * 2).coerceAtLeast(4)
        val innerH = (h - INNER_PAD_PX * 2).coerceAtLeast(4)
        val measurer = StaticTextMeasurer(context)
        val fit = AutoFitTextSizer.fit(
            text = arabic,
            maxWidthPx = innerW,
            maxHeightPx = innerH,
            minPx = MIN_TEXT_PX,
            maxPx = (innerH * 0.62f).coerceAtLeast(MIN_TEXT_PX + 2f),
            measurer = measurer
        )
        if (!fit.fits) {
            Log.w(TAG, "patch #$boxId: text did not fit at min size — clipped after bubble-interior growth (patch=${w}x${h})")
        }
        val tf = typeface(context)
        val textPaint = TextPaintFactory.create(tf, fit.fontSizePx, textColor)
        val layout = android.text.StaticLayout.Builder.obtain(arabic, 0, arabic.length, textPaint, innerW)
            // Arabic is RTL: ALIGN_NORMAL aligns to the start (right edge) so the
            // translated bubble is right-aligned, never overflowing the box.
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.15f)
            .setIncludePad(false)
            .setMaxLines(4)
            .build()
        val textBlockH = min(layout.height, innerH).toFloat()
        val originY = INNER_PAD_PX + (innerH - textBlockH) / 2f
        canvas.save()
        canvas.clipRect(Rect(INNER_PAD_PX, INNER_PAD_PX, w - INNER_PAD_PX, h - INNER_PAD_PX))
        canvas.translate(INNER_PAD_PX.toFloat(), originY)
        if (complexBg) {
            val outlineColor = if (ColorSampler.luminance(textColor) < 140.0) 0xCCFFFFFF.toInt() else 0xCC000000.toInt()
            val outlinePaint = TextPaintFactory.create(tf, fit.fontSizePx, outlineColor)
            outlinePaint.style = Paint.Style.STROKE
            outlinePaint.strokeWidth = max(2f, fit.fontSizePx * 0.16f)
            outlinePaint.strokeJoin = Paint.Join.ROUND
            val outlineLayout = android.text.StaticLayout.Builder.obtain(arabic, 0, arabic.length, outlinePaint, innerW)
                .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.15f)
                .setIncludePad(false)
                .setMaxLines(4)
                .build()
            outlineLayout.draw(canvas)
        }
        layout.draw(canvas)
        canvas.restore()
    }
}

/** Factory for configured TextPaint (kept out of the public API). */
private object TextPaintFactory {
    fun create(typeface: Typeface, textSizePx: Float, color: Int): android.text.TextPaint =
        android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            this.textSize = textSizePx
            this.color = color
        }
}
