package com.manhwalens.app.render

/**
 * Background-color sampling for bubble interiors.
 *
 * Input is a raw ARGB pixel array (row-major, [width * height]) so the function
 * is pure Kotlin and unit-testable on the JVM without android.graphics.
 *
 * Strategy: quantize each pixel's channels to 5 bits (32 buckets/channel),
 * histogram, take the most frequent bucket, then average the EXACT pixels of
 * that bucket. Thin text strokes spread their pixels over many buckets, so the
 * background dominates unless the box is mostly glyphs (tiny SFX), which is
 * exactly what we want.
 */
object ColorSampler {

    private const val ALPHA_MIN = 128
    private const val SHIFT = 3               // 5-bit channels
    private const val BUCKETS = 32

    /**
     * Sample the dominant interior background color of [left, top, right, bottom)
     * inside a [imgW]x[imgH] image. The box is clamped to the image bounds.
     * Returns an opaque ARGB color, or null when the region is empty/fully transparent.
     */
    fun sample(
        pixels: IntArray,
        imgW: Int,
        imgH: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): Int? {
        if (imgW <= 0 || imgH <= 0 || pixels.size < imgW * imgH) return null
        val l = left.coerceIn(0, imgW)
        val t = top.coerceIn(0, imgH)
        val r = right.coerceIn(0, imgW)
        val b = bottom.coerceIn(0, imgH)
        if (r - l < 2 || b - t < 2) return null

        // 5-bit histogram over opaque pixels
        val counts = IntArray(BUCKETS * BUCKETS * BUCKETS)
        var used = 0
        for (y in t until b) {
            val row = y * imgW
            for (x in l until r) {
                val px = pixels[row + x]
                if (px ushr 24 < ALPHA_MIN) continue
                val rq = ((px shr 16 and 0xFF) shr SHIFT) and 0x1F
                val gq = ((px shr 8 and 0xFF) shr SHIFT) and 0x1F
                val bq = ((px and 0xFF) shr SHIFT) and 0x1F
                counts[(rq shl 10) or (gq shl 5) or bq]++
                used++
            }
        }
        if (used == 0) return null

        // Dominant bucket
        var bestIdx = -1
        var bestCount = 0
        for (i in counts.indices) {
            if (counts[i] > bestCount) {
                bestCount = counts[i]
                bestIdx = i
            }
        }

        // Average exact pixels of the dominant bucket
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0L
        for (y in t until b) {
            val row = y * imgW
            for (x in l until r) {
                val px = pixels[row + x]
                if (px ushr 24 < ALPHA_MIN) continue
                val rq = ((px shr 16 and 0xFF) shr SHIFT) and 0x1F
                val gq = ((px shr 8 and 0xFF) shr SHIFT) and 0x1F
                val bq = ((px and 0xFF) shr SHIFT) and 0x1F
                if (((rq shl 10) or (gq shl 5) or bq) == bestIdx) {
                    sr += px shr 16 and 0xFF
                    sg += px shr 8 and 0xFF
                    sb += px and 0xFF
                    n++
                }
            }
        }
        if (n == 0L) return null
        val rr = (sr / n).toInt()
        val gg = (sg / n).toInt()
        val bb = (sb / n).toInt()
        return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }

    /** Simple Rec.601-ish luma in [0, 255]. */
    fun luminance(argb: Int): Double {
        val r = (argb shr 16 and 0xFF).toDouble()
        val g = (argb shr 8 and 0xFF).toDouble()
        val b = (argb and 0xFF).toDouble()
        return 0.299 * r + 0.587 * g + 0.114 * b
    }

    /** White or near-black, whichever contrasts better against [bg]. */
    fun bestTextColorOn(bg: Int): Int =
        if (luminance(bg) < 140.0) 0xFFFFFFFF.toInt() else 0xFF1B1A24.toInt()
}

/** True when [bg] is a mid-luma color where neither black nor white reaches strong contrast. */
fun needsOutline(bg: Int): Boolean {
    val l = ColorSampler.luminance(bg)
    return l in 70.0..190.0
}
