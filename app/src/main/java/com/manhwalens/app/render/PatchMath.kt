package com.manhwalens.app.render

import com.manhwalens.app.ocr.geometry.Rect2D
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure-pixel patch math (Part B) — operates on raw ARGB int buffers so every
 * function is unit-testable on the JVM.
 *
 * Conventions: coordinates are pixels in the WORKING BUFFER (crop or frame).
 * Rects are half-open [left, right) × [top, bottom) floats (see Rect2D).
 */
object PatchMath {

    // ---------- geometry ----------

    /**
     * Union of [rects] expanded by [pad] px, clamped to the [frameW]×[frameH] frame.
     * This is the patch's base rect — the union of the block's OCR line boxes + pad
     * (the block bounds ARE the union of its line boxes).
     */
    fun unionWithPad(rects: List<Rect2D>, pad: Float, frameW: Int, frameH: Int): Rect2D? {
        if (rects.isEmpty() || frameW <= 0 || frameH <= 0) return null
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (rc in rects) {
            if (rc.left < l) l = rc.left
            if (rc.top < t) t = rc.top
            if (rc.right > r) r = rc.right
            if (rc.bottom > b) b = rc.bottom
        }
        l = (l - pad).coerceIn(0f, frameW - 1f)
        t = (t - pad).coerceIn(0f, frameH - 1f)
        r = (r + pad).coerceIn(l + 1f, frameW.toFloat())
        b = (b + pad).coerceIn(t + 1f, frameH.toFloat())
        return Rect2D(l, t, r, b)
    }

    /**
     * Hard bound for bubble-interior expansion ("max 1.4× text box"): the returned
     * rect shares [textRect]'s center, is at most [maxAxisScale]× on each axis,
     * and is clamped to the frame. The flood fill may not cross it.
     */
    fun expansionBound(textRect: Rect2D, frameW: Int, frameH: Int, maxAxisScale: Float = 1.4f): Rect2D {
        val halfW = textRect.width * maxAxisScale / 2f
        val halfH = textRect.height * maxAxisScale / 2f
        val cx = textRect.centerX
        val cy = textRect.centerY
        val l = (cx - halfW).coerceIn(0f, (frameW - 1).toFloat())
        val t = (cy - halfH).coerceIn(0f, (frameH - 1).toFloat())
        val r = (cx + halfW).coerceIn(l + 1f, frameW.toFloat())
        val b = (cy + halfH).coerceIn(t + 1f, frameH.toFloat())
        return Rect2D(l, t, r, b)
    }

    // ---------- ring sampling ----------

    data class RingSample(
        /** Per-channel median colour (opaque). */
        val color: Int,
        /** RMS of per-channel stddevs — 0 = perfectly flat ring. */
        val stddev: Double,
        /** True when the ring is flat enough to fill with a solid colour. */
        val uniform: Boolean,
        val pixelCount: Int
    ) {
        companion object {
            /** Max RMS per-channel stddev (0–255) still considered a uniform ring. */
            const val UNIFORM_STDDEV = 14.0
        }
    }

    /**
     * Sample the ring of pixels in the band of [ringWidth] px that surrounds [inner],
     * excluding [inner] itself. Returns null when fewer than [MIN_RING_PX] pixels are
     * sampleable (rect touches nothing usable).
     */
    fun sampleRing(
        pixels: IntArray, w: Int, h: Int,
        inner: Rect2D, ringWidth: Int = 4, minRingPx: Int = 12
    ): RingSample? {
        if (w <= 0 || h <= 0 || pixels.size < w * h || ringWidth < 1) return null
        val il = inner.left.toInt().coerceIn(0, w)
        val it = inner.top.toInt().coerceIn(0, h)
        val ir = inner.right.toInt().coerceIn(0, w)
        val ib = inner.bottom.toInt().coerceIn(0, h)
        val ol = (il - ringWidth).coerceAtLeast(0); val ot = (it - ringWidth).coerceAtLeast(0)
        val orr = (ir + ringWidth).coerceAtMost(w); val ob = (ib + ringWidth).coerceAtMost(h)
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        for (y in ot until ob) {
            for (x in ol until orr) {
                val inInner = x in il until ir && y in it until ib
                if (inInner) continue
                val px = pixels[y * w + x]
                if (px ushr 24 < 128) continue
                rs += (px shr 16) and 0xFF
                gs += (px shr 8) and 0xFF
                bs += px and 0xFF
            }
        }
        val n = rs.size
        if (n < minRingPx) return null
        val mr = medianOf(rs); val mg = medianOf(gs); val mb = medianOf(bs)
        val vr = varianceOf(rs, mr); val vg = varianceOf(gs, mg); val vb = varianceOf(bs, mb)
        val stddev = sqrt((vr + vg + vb) / 3.0)
        return RingSample(
            color = (0xFF shl 24) or (mr shl 16) or (mg shl 8) or mb,
            stddev = stddev,
            uniform = stddev <= RingSample.UNIFORM_STDDEV,
            pixelCount = n
        )
    }

    private fun medianOf(xs: List<Int>): Int {
        val sorted = xs.sorted()
        val m = sorted.size
        return if (m % 2 == 1) sorted[m / 2] else (sorted[m / 2 - 1] + sorted[m / 2]) / 2
    }

    private fun varianceOf(xs: List<Int>, mean: Int): Double {
        var acc = 0.0
        for (x in xs) {
            val d = (x - mean).toDouble()
            acc += d * d
        }
        return acc / xs.size
    }

    // ---------- bounded flood fill (bubble interior growth) ----------

    /**
     * Grow from just OUTSIDE [seedRect]'s edges into neighbouring pixels whose colour
     * is within [delta] per-channel of [refColor]. Dark pixels (luma < [outlineLuma])
     * are treated as outlines/strokes and never entered. Growth may not leave [bound]
     * (buffer coords; clamped to the buffer). Returns the bounding rect of the reached
     * region ∪ [seedRect], or null when not a single seed pixel matched.
     *
     * Bounded: every reachable pixel is visited at most once → O(w·h) worst case, a
     * crop of a few hundred px per side in practice.
     */
    fun floodRegion(
        pixels: IntArray, w: Int, h: Int,
        seedRect: Rect2D,
        refColor: Int,
        bound: Rect2D,
        delta: Int = 26,
        outlineLuma: Int = 80
    ): Rect2D? {
        if (w <= 0 || h <= 0 || pixels.size < w * h) return null
        val l = floor(seedRect.left).toInt().coerceIn(0, w - 1)
        val t = floor(seedRect.top).toInt().coerceIn(0, h - 1)
        val r = ceil(seedRect.right).toInt().coerceIn(l + 1, w)
        val b = ceil(seedRect.bottom).toInt().coerceIn(t + 1, h)
        val bl = floor(bound.left).toInt().coerceIn(0, w - 1)
        val bt = floor(bound.top).toInt().coerceIn(0, h - 1)
        val br = ceil(bound.right).toInt().coerceIn(bl + 1, w)
        val bb = ceil(bound.bottom).toInt().coerceIn(bt + 1, h)
        val refR = (refColor shr 16) and 0xFF; val refG = (refColor shr 8) and 0xFF; val refB = refColor and 0xFF
        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0; var tail = 0
        fun seed(x: Int, y: Int) {
            if (x < bl || y < bt || x >= br || y >= bb) return
            val i = y * w + x
            if (visited[i]) return
            visited[i] = true
            queue[tail++] = i
        }
        for (x in l until r) { seed(x, t - 1); seed(x, b) }
        for (y in t until b) { seed(l - 1, y); seed(r, y) }
        if (tail == 0) return null
        var minX = Int.MAX_VALUE; var minY = Int.MAX_VALUE; var maxX = -1; var maxY = -1
        while (head < tail) {
            val i = queue[head++]
            val y = i / w
            val x = i % w
            val c = pixels[i]
            val pr = (c shr 16) and 0xFF; val pg = (c shr 8) and 0xFF; val pb = c and 0xFF
            val luma = (pr * 299 + pg * 587 + pb * 114) / 1000
            if (luma < outlineLuma) continue
            if (abs(pr - refR) > delta || abs(pg - refG) > delta || abs(pb - refB) > delta) continue
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
            if (x + 1 < br) seed(x + 1, y)
            if (x - 1 >= bl) seed(x - 1, y)
            if (y + 1 < bb) seed(x, y + 1)
            if (y - 1 >= bt) seed(x, y - 1)
        }
        if (maxX < 0) return null
        minX = min(minX, l); minY = min(minY, t)
        maxX = max(maxX, r - 1); maxY = max(maxY, b - 1)
        return Rect2D(minX.toFloat(), minY.toFloat(), (maxX + 1).toFloat(), (maxY + 1).toFloat())
    }

    // ---------- text mask ----------

    /**
     * Mark pixels that belong to the TEXT (as opposed to the bubble background):
     * per-channel delta from [refColor] exceeds [delta], OR the pixel is dark
     * (luma < [strokeLuma] — glyph strokes/outlines). The mask is then dilated by
     * [dilate] px so anti-alias halos are covered too. Returned mask is buffer-sized.
     */
    fun buildTextMask(
        pixels: IntArray, w: Int, h: Int,
        refColor: Int,
        delta: Int = 42,
        strokeLuma: Int = 110,
        dilate: Int = 2
    ): BooleanArray {
        val n = w * h
        val mask = BooleanArray(n)
        if (pixels.size < n) return mask
        val refR = (refColor shr 16) and 0xFF; val refG = (refColor shr 8) and 0xFF; val refB = refColor and 0xFF
        val refLuma = (refR * 299 + refG * 587 + refB * 114) / 1000
        // "Dark strokes" only make sense on a LIGHT background; on a dark background the
        // per-channel delta rule alone catches the (light) glyphs.
        val darkStrokesApply = refLuma >= strokeLuma
        for (i in 0 until n) {
            val px = pixels[i]
            val pr = (px shr 16) and 0xFF; val pg = (px shr 8) and 0xFF; val pb = px and 0xFF
            val luma = (pr * 299 + pg * 587 + pb * 114) / 1000
            val differs = abs(pr - refR) > delta || abs(pg - refG) > delta || abs(pb - refB) > delta
            mask[i] = differs || (darkStrokesApply && luma < strokeLuma)
        }
        return if (dilate > 0) dilateMask(mask, w, h, dilate) else mask
    }

    /** Box dilation (fast two-pass). */
    fun dilateMask(mask: BooleanArray, w: Int, h: Int, radius: Int): BooleanArray {
        if (radius <= 0) return mask.copyOf()
        var current = mask
        // Iterative 3x3 box dilate — radius is tiny (2).
        repeat(radius) {
            val out = current.copyOf()
            for (y in 0 until h) {
                for (x in 0 until w) {
                    if (current[y * w + x]) continue
                    var hit = false
                    var dy = -1
                    while (dy <= 1 && !hit) {
                        var dx = -1
                        while (dx <= 1 && !hit) {
                            val nx = x + dx; val ny = y + dy
                            if (nx in 0 until w && ny in 0 until h && current[ny * w + nx]) hit = true
                            dx++
                        }
                        dy++
                    }
                    if (hit) out[y * w + x] = true
                }
            }
            current = out
        }
        return current
    }

    // ---------- inpainting (iterative neighbour averaging) ----------

    /**
     * Fill every masked pixel with the average of its unmasked 8-connected neighbours,
     * iterating until the wavefront reaches the region center or [maxIterations] runs
     * out. This is a lightweight Telea-style fill — accurate enough for text removal
     * over smooth/gradient bubble backgrounds. Returns the iteration count actually used.
     */
    fun inpaintMasked(pixels: IntArray, w: Int, h: Int, mask: BooleanArray, maxIterations: Int = 256): Int {
        if (pixels.size < w * h || mask.size < w * h) return 0
        var current = mask.copyOf()
        val newMaskBase = BooleanArray(w * h)
        var iter = 0
        while (iter < maxIterations) {
            val accR = IntArray(w * h); val accG = IntArray(w * h); val accB = IntArray(w * h)
            val accN = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!current[i]) continue
                    var r = 0; var g = 0; var b = 0; var cnt = 0
                    for (dy in -1..1) {
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = x + dx; val ny = y + dy
                            if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                            val j = ny * w + nx
                            if (current[j]) continue
                            val c = pixels[j]
                            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
                            cnt++
                        }
                    }
                    if (cnt > 0) {
                        accR[i] = r; accG[i] = g; accB[i] = b; accN[i] = cnt
                    }
                }
            }
            var filled = 0
            for (i in 0 until w * h) {
                if (accN[i] > 0) {
                    val r = accR[i] / accN[i]; val g = accG[i] / accN[i]; val b = accB[i] / accN[i]
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    newMaskBase[i] = true
                    filled++
                }
            }
            iter++
            if (filled == 0) break
            // Pixels just filled join the known region for the next wavefront.
            for (i in 0 until w * h) if (newMaskBase[i]) current[i] = false
        }
        return iter
    }

    /**
     * Count pixels in [region] that still look like text: differ from [refColor] by
     * more than [delta] per-channel or are darker than [strokeLuma]. Used by unit
     * tests to prove the inpainter removed all glyph pixels.
     */
    fun countSuspectPixels(
        pixels: IntArray, w: Int, h: Int,
        region: Rect2D,
        refColor: Int,
        delta: Int = 42,
        strokeLuma: Int = 110
    ): Int {
        val refR = (refColor shr 16) and 0xFF; val refG = (refColor shr 8) and 0xFF; val refB = refColor and 0xFF
        val refLuma = (refR * 299 + refG * 587 + refB * 114) / 1000
        val darkStrokesApply = refLuma >= strokeLuma
        val l = floor(region.left).toInt().coerceIn(0, w)
        val t = floor(region.top).toInt().coerceIn(0, h)
        val r = ceil(region.right).toInt().coerceIn(l, w)
        val b = ceil(region.bottom).toInt().coerceIn(t, h)
        var count = 0
        for (y in t until b) {
            for (x in l until r) {
                val c = pixels[y * w + x]
                val pr = (c shr 16) and 0xFF; val pg = (c shr 8) and 0xFF; val pb = c and 0xFF
                val luma = (pr * 299 + pg * 587 + pb * 114) / 1000
                val differs = abs(pr - refR) > delta || abs(pg - refG) > delta || abs(pb - refB) > delta
                if (differs || (darkStrokesApply && luma < strokeLuma)) count++
            }
        }
        return count
    }

    /** Average opaque colour of a buffer region (fallback for text-colour choice). */
    fun averageColor(pixels: IntArray, w: Int, h: Int, region: Rect2D): Int? {
        val l = floor(region.left).toInt().coerceIn(0, w)
        val t = floor(region.top).toInt().coerceIn(0, h)
        val r = ceil(region.right).toInt().coerceIn(l, w)
        val b = ceil(region.bottom).toInt().coerceIn(t, h)
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0L
        for (y in t until b) for (x in l until r) {
            val px = pixels[y * w + x]
            if (px ushr 24 < 128) continue
            sr += (px shr 16) and 0xFF; sg += (px shr 8) and 0xFF; sb += px and 0xFF
            n++
        }
        if (n == 0L) return null
        return (0xFF shl 24) or (((sr / n).toInt()) shl 16) or (((sg / n).toInt()) shl 8) or (sb / n).toInt()
    }
}
