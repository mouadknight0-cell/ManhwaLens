package com.manhwalens.app.capture

import kotlin.math.max
import kotlin.math.min

/**
 * v0.13.0 — capture fast path: pure math + buffer swizzle for the mirror session.
 *
 * The app has always capped the working frame to [WORKING_MAX_WIDTH] px (the OCR and the
 * overlay are built for it), but the mirror used to render at display resolution and the
 * cap was applied AFTER a full-res copy — paying for a ~10 MB RGBA buffer, a bitmap alloc
 * and a scale pass on every capture. The mirror now renders at the working size directly:
 * identical pixels to the old `createScaledBitmap(bitmap, 720, …)` output, minus that work.
 */
object MirrorSizing {

    const val WORKING_MAX_WIDTH = 720

    /**
     * Mirror dimensions for a display of [displayW]x[displayH]: width capped at
     * [maxWidth], aspect ratio preserved, never upscales. Invalid input is returned
     * unchanged (the caller has already validated the display size).
     */
    fun workingSize(displayW: Int, displayH: Int, maxWidth: Int = WORKING_MAX_WIDTH): Pair<Int, Int> {
        if (displayW <= 0 || displayH <= 0 || maxWidth <= 0) return displayW to displayH
        val w = min(displayW, maxWidth)
        if (w == displayW) return displayW to displayH
        val h = max(1, (displayH * (w.toFloat() / displayW.toFloat())).toInt())
        return w to h
    }
}

/**
 * v0.13.0 — turn an ImageReader RGBA_8888 buffer into ARGB_8888 ints for
 * [android.graphics.Bitmap.createBitmap].
 *
 * RGBA_8888 memory order (little-endian) is R,G,B,A bytes, i.e. the uint32 is
 * `0xAABBGGRR`; Bitmap ARGB_8888 wants `0xAARRGGBB`. Red and blue are swapped in place
 * with three ops per pixel instead of the old four-shift/or byte dance. Pure JVM +
 * java.nio.ByteBuffer so the mapping is unit-tested. Callers must treat the input
 * buffer as READ-ONLY input (it is not modified).
 */
object RgbaSwizzle {

    /** `0xAABBGGRR` -> `0xAARRGGBB`. Alpha and green stay in place. */
    fun swizzleInt(v: Int): Int =
        (v and 0xFF00FF00.toInt()) or
            ((v and 0xFF) shl 16) or
            ((v ushr 16) and 0xFF)

    /**
     * Copy [width]x[height] pixels out of [buffer] with the given [rowStride] into a fresh
     * IntArray in row-major order. Uses the aligned-int fast path when rows are packed
     * (rowStride == width*4) and a byte path otherwise. Never throws for strided input;
     * returns a full-size array either way.
     */
    fun swab(buffer: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int): IntArray {
        require(width > 0 && height > 0) { "width/height must be positive" }
        require(rowStride >= width * 4) { "rowStride $rowStride smaller than a packed row (${width * 4})" }
        val out = IntArray(width * height)
        val dup = buffer.duplicate() // independent position; never touches the caller's buffer state
        if (rowStride == width * 4) {
            dup.order(java.nio.ByteOrder.LITTLE_ENDIAN)
            var o = 0
            val pixels = width * height
            var i = 0
            while (i < pixels) {
                out[o] = swizzleInt(dup.getInt(i * 4))
                o++
                i++
            }
            return out
        }
        // Strided rows (padded stride or crop padding): byte path.
        val row = ByteArray(width * 4)
        var o = 0
        for (y in 0 until height) {
            dup.position(y * rowStride)
            dup.get(row, 0, row.size)
            var i = 0
            while (i < row.size) {
                val r = row[i].toInt() and 0xFF
                val g = row[i + 1].toInt() and 0xFF
                val b = row[i + 2].toInt() and 0xFF
                val a = row[i + 3].toInt() and 0xFF
                out[o] = (a shl 24) or (r shl 16) or (g shl 8) or b
                o++
                i += 4
            }
        }
        return out
    }
}
