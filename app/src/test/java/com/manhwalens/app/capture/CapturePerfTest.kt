package com.manhwalens.app.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * v0.13.0 — working-resolution mirror sizing + RGBA→ARGB swizzle.
 * The swizzle must be pixel-identical to the old per-pixel loop.
 */
class CapturePerfTest {

    // ---------- MirrorSizing ----------

    @Test
    fun wideDisplayIsCappedToWorkingWidthWithAspectPreserved() {
        val (w, h) = MirrorSizing.workingSize(1080, 2400)
        assertEquals(720, w)
        assertEquals(1600, h) // 2400 * 720/1080
    }

    @Test
    fun narrowDisplayIsNeverUpscaled() {
        assertEquals(360 to 800, MirrorSizing.workingSize(360, 800))
    }

    @Test
    fun exactlyAtCapIsUnchanged() {
        assertEquals(720 to 1280, MirrorSizing.workingSize(720, 1280))
    }

    @Test
    fun invalidInputPassesThrough() {
        assertEquals(0 to 0, MirrorSizing.workingSize(0, 0))
        assertEquals(-5 to 100, MirrorSizing.workingSize(-5, 100))
    }

    @Test
    fun customCapIsHonored() {
        val (w, h) = MirrorSizing.workingSize(1080, 1920, maxWidth = 540)
        assertEquals(540, w)
        assertEquals(960, h)
    }

    // ---------- RgbaSwizzle ----------

    /** Reference: the old per-pixel loop from imageToBitmap. */
    private fun referenceSwab(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int): IntArray {
        val out = IntArray(width * height)
        val row = ByteArray(width * 4)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            buffer.get(row)
            var i = 0
            var p = y * width
            while (i < row.size) {
                val r = row[i].toInt() and 0xFF
                val g = row[i + 1].toInt() and 0xFF
                val b = row[i + 2].toInt() and 0xFF
                val a = row[i + 3].toInt() and 0xFF
                out[p] = (a shl 24) or (r shl 16) or (g shl 8) or b
                i += 4
                p++
            }
        }
        return out
    }

    private fun rgbaBuffer(width: Int, height: Int, rowStride: Int, seed: Int): ByteBuffer {
        val buf = ByteBuffer.allocate(rowStride * height).order(ByteOrder.LITTLE_ENDIAN)
        var v = seed
        for (i in 0 until rowStride * height) {
            v = (v * 1103515245 + 12345) and 0x7FFFFFFF
            buf.put(i, ((v ushr 16) and 0xFF).toByte())
        }
        return buf
    }

    @Test
    fun swizzleIntSwapsRedAndBlueOnly() {
        // 0xAABBGGRR -> 0xAARRGGBB
        assertEquals(0x11223344, RgbaSwizzle.swizzleInt(0x11443322))
        assertEquals(0xFF0000FF.toInt(), RgbaSwizzle.swizzleInt(0xFFFF0000.toInt())) // RGBA blue -> ARGB blue
        assertEquals(0xFF00FF00.toInt(), RgbaSwizzle.swizzleInt(0xFF00FF00.toInt())) // green untouched
        assertEquals(0x00000000, RgbaSwizzle.swizzleInt(0x00000000))
    }

    @Test
    fun packedRowsMatchReferenceLoop() {
        val w = 37
        val h = 23
        val buf = rgbaBuffer(w, h, w * 4, seed = 7)
        assertArrayEquals(referenceSwab(buf, w, h, w * 4), RgbaSwizzle.swab(buf, w, h, w * 4))
    }

    @Test
    fun paddedRowsMatchReferenceLoop() {
        val w = 29
        val h = 17
        val stride = w * 4 + 13 // padding bytes between rows
        val buf = rgbaBuffer(w, h, stride, seed = 99)
        assertArrayEquals(referenceSwab(buf, w, h, stride), RgbaSwizzle.swab(buf, w, h, stride))
    }

    @Test
    fun swabDoesNotTouchTheCallerBufferPosition() {
        val w = 11
        val h = 5
        val buf = rgbaBuffer(w, h, w * 4, seed = 3)
        buf.position(4)
        RgbaSwizzle.swab(buf, w, h, w * 4)
        assertEquals(4, buf.position())
    }

    @Test
    fun singlePixel() {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(0, 0x11.toByte()) // R
        buf.put(1, 0x22.toByte()) // G
        buf.put(2, 0x33.toByte()) // B
        buf.put(3, 0x44.toByte()) // A
        assertArrayEquals(intArrayOf(0x44112233), RgbaSwizzle.swab(buf, 1, 1, 4))
    }
}
