package com.manhwalens.app.render

import android.content.Context
import android.graphics.Bitmap
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

/**
 * Rasterizes one Arabic patch for a bubble (Part B).
 *
 * Strategy per bubble:
 *  1. text rect = block bounds (union of OCR line boxes) + [PAD_PX] pad — the base patch;
 *  2. try to EXPAND into the bubble interior with a bounded flood fill over pixels
 *     similar to the bubble background, stopping at dark outline strokes and never
 *     beyond 1.4× the text box ([PatchMath.expansionBound]);
 *  3. sample the ring around the TEXT area (excluding glyphs):
 *       · uniform ring → fill the patch rect with the median ring colour, FULLY OPAQUE;
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

    /**
     * Build the patch for [box] sampled from [source]. Returns null on unusable input.
     *
     * [cover] (v0.11.0) is the opaque area this box must erase. The caller derives it from
     * [PatchCoverage] so that (a) the patch always paints over the WHOLE union of OCR
     * boxes of the bubble, and (b) a bubble that OCR split into several blocks is erased
     * once, by its owner. When null the box bounds are used.
     */
    fun build(
        context: Context,
        source: Bitmap,
        box: BubbleRenderBox,
        cover: com.manhwalens.app.ocr.geometry.Rect2D? = null,
        /**
         * v0.11.0: this block's own OCR box in FRAME coordinates. Blocks that share a cover
         * rect keep their Arabic inside their own band of that rect, so two blocks of one
         * bubble stack into one readable text instead of drawing on top of each other. The
         * patch itself always covers the WHOLE [cover], so both bands are erased of English.
         */
        band: com.manhwalens.app.ocr.geometry.Rect2D? = null
    ): BubblePatch? {
        if (source.isRecycled || box.arabic.isBlank()) return null
        val frameW = source.width
        val frameH = source.height

        // ---------- 1) base text rect ----------
        // The cover rect from PatchCoverage already includes the union of every OCR box of
        // this bubble plus its dilation, so the pad is applied only when no cover came in.
        val textRect = com.manhwalens.app.ocr.geometry.Rect2D(
            box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat()
        )
        val baseRect = cover?.let { c ->
            com.manhwalens.app.ocr.geometry.Rect2D(
                min(c.left, textRect.left),
                min(c.top, textRect.top),
                max(c.right, textRect.right),
                max(c.bottom, textRect.bottom)
            )
        } ?: textRect
        val inner = PatchMath.unionWithPad(
            listOf(baseRect),
            if (cover != null) 0f else PAD_PX.toFloat(), frameW, frameH
        ) ?: return null

        // ---------- 2) grow the opaque area (bounded flood fill) ----------
        // The cover rect from PatchCoverage is a FLOOR, never a ceiling: growing past it is
        // safe and desirable (it erases more English), and PatchCoverage already merged the
        // bubbles that overlap, so patches no longer smear into each other's Arabic. Only
        // the flood fill keeps the growth inside the bubble (it stops at outline strokes
        // and at 1.4× the text box), so neighbouring bubbles are never swallowed.
        val bound = PatchMath.expansionBound(inner, frameW, frameH, MAX_AXIS_SCALE)
        val patchRect = tryExpandToBubble(source, inner, bound, box.backgroundColor, cover != null)
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
            buildFromCrop(context, crop, w, h, left, top, box, inner, frameW, frameH, band)
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
        refColor: Int,
        /** Union the flood area with [inner] instead of replacing it (explicit cover). */
        keepInner: Boolean = false
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
                ?.let { r ->
                    // Flood starts at the text rect, so the union is exact; keeping the union
                    // guarantees the whole cover survives even if the fill starves.
                    if (!keepInner) r else com.manhwalens.app.ocr.geometry.Rect2D(
                        min(r.left, localInner.left), min(r.top, localInner.top),
                        max(r.right, localInner.right), max(r.bottom, localInner.bottom)
                    )
                }
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
        frameH: Int,
        band: com.manhwalens.app.ocr.geometry.Rect2D? = null
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
        // v0.11.0: the patch is painted FULLY OPAQUE. The old feathered edge blended to
        // transparency, so the English glyphs underneath showed through the blend ring —
        // exactly the "English still visible around the Arabic" defect.
        val patch = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(patch)
        if (uniformRing) {
            paintSolidOpaque(canvas, w, h, bgColor)
        } else {
            // Non-uniform ring (gradient / art): keep the crop, erase glyphs by mask+inpaint.
            val work = crop.copyOf()
            val dominant = ColorSampler.sample(work, w, h, 0, 0, w, h) ?: box.backgroundColor
            val mask = PatchMath.buildTextMask(work, w, h, dominant, delta = 42, strokeLuma = 110, dilate = 2)
            PatchMath.inpaintMasked(work, w, h, mask)
            // Opaque crop: the glyph mask was already inpainted, so every pixel is real
            // bubble art. No feathering — a soft edge would blend to transparency and let
            // the English underneath show through (v0.11.0 defect).
            val cropBmp = Bitmap.createBitmap(work, 0, w, w, h, Bitmap.Config.ARGB_8888)
            canvas.drawBitmap(cropBmp, 0f, 0f, null)
            cropBmp.recycle()
        }

        // ---------- Arabic text ----------
        val textBg = if (uniformRing) bgColor else (PatchMath.averageColor(crop, w, h, localInner) ?: bgColor)
        val textColor = ColorSampler.bestTextColorOn(textBg)
        val complexBg = !uniformRing || needsOutline(textBg)
        // Text area: the block's own band inside the shared cover (patch-local pixels), or
        // the whole patch when the block has the area to itself.
        val bandTop: Int
        val bandHeight: Int
        if (band != null) {
            bandTop = (band.top - frameTop).toInt().coerceIn(0, h - MIN_PATCH_PX)
            bandHeight = (band.bottom - band.top).toInt().coerceIn(MIN_PATCH_PX, h - bandTop)
        } else {
            bandTop = 0
            bandHeight = h
        }
        drawArabicText(
            context, canvas, box.arabic, w, bandTop, bandHeight, textColor, complexBg, box.id,
            anchored = band != null
        )

        return BubblePatch(
            id = box.id,
            left = frameLeft,
            top = frameTop,
            right = frameLeft + w,
            bottom = frameTop + h,
            bitmap = patch
        )
    }

    /**
     * Fill the whole patch rect opaquely (no blur, no transparency): every pixel inside the
     * patch must be the bubble colour, because the original glyphs are still in the frame
     * underneath. Erode-then-draw keeps the corners rounded without leaving holes.
     */
    private fun paintSolidOpaque(canvas: Canvas, w: Int, h: Int, bg: Int) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = bg
            isDither = true
        }
        // Full rect first: guarantees zero transparent pixels, so no glyph can peek out.
        canvas.drawRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), bgPaint)
        val corner = min(18f, min(w, h) * 0.22f)
        if (corner > 1f) canvas.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), corner, corner, bgPaint)
    }

    /**
     * Draw the Arabic inside the band [bandTop]..[bandTop]+[bandHeight] of a patch that is
     * [w] px wide. [anchored] keeps the text at the band's top (stacked blocks of one bubble);
     * otherwise it is vertically centred (single-block patches read better that way).
     */
    private fun drawArabicText(
        context: Context,
        canvas: Canvas,
        arabic: String,
        w: Int,
        bandTop: Int,
        bandHeight: Int,
        textColor: Int,
        complexBg: Boolean,
        boxId: Int,
        anchored: Boolean = false
    ) {
        val innerW = (w - INNER_PAD_PX * 2).coerceAtLeast(4)
        val innerH = (bandHeight - INNER_PAD_PX * 2).coerceAtLeast(4)
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
            Log.w(TAG, "patch #$boxId: text did not fit at min size — clipped after bubble-interior growth (patch=${w}x${bandHeight})")
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
        // Stacked blocks anchor their Arabic at the TOP of their band; otherwise it is centred.
        val originY = bandTop + INNER_PAD_PX + if (anchored) 0f else (innerH - textBlockH) / 2f
        canvas.save()
        canvas.clipRect(
            Rect(INNER_PAD_PX, bandTop + INNER_PAD_PX, w - INNER_PAD_PX, bandTop + bandHeight - INNER_PAD_PX)
        )
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
