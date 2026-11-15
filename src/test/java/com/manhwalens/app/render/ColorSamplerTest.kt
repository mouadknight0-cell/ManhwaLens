package com.manhwalens.app.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ColorSamplerTest {

    private fun argb(r: Int, g: Int, b: Int, a: Int = 255): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    /** Build a w×h image filled with [fill], then stamp [stroke] lines. */
    private fun makeImage(
        w: Int, h: Int, fill: Int,
        strokes: List<Pair<IntRange, Int>> = emptyList() // y-range → color
    ): IntArray {
        val px = IntArray(w * h) { fill }
        for ((yRange, color) in strokes) {
            for (y in yRange) {
                if (y < 0 || y >= h) continue
                for (x in 0 until w) px[y * w + x] = color
            }
        }
        return px
    }

    private fun ch(color: Int, shift: Int) = (color shr shift) and 0xFF

    @Test
    fun solidWhiteBubbleSamplesNearWhite() {
        val px = makeImage(40, 40, argb(255, 255, 255), listOf(8..11 to argb(12, 12, 12)))
        val out = ColorSampler.sample(px, 40, 40, 0, 0, 40, 40)!!
        assertTrue("R~255, got ${ch(out, 16)}", abs(ch(out, 16) - 255) <= 4)
        assertTrue(abs(ch(out, 8) - 255) <= 4)
        assertTrue(abs(ch(out, 0) - 255) <= 4)
    }

    @Test
    fun coloredBubbleWithTextStrokesSamplesTheColorNotTheGlyphs() {
        // teal-ish bubble with ~35% black text rows
        val px = makeImage(
            40, 40, argb(90, 180, 170),
            listOf(6..8 to argb(20, 20, 20), 16..19 to argb(20, 20, 20), 26..27 to argb(20, 20, 20))
        )
        val out = ColorSampler.sample(px, 40, 40, 0, 0, 40, 40)!!
        assertTrue("G should stay teal-ish, got ${ch(out, 8)}", ch(out, 8) in 150..210)
        assertTrue(ch(out, 16) in 60..120)
    }

    @Test
    fun darkBubbleWithWhiteTextStrokesSamplesDark() {
        val px = makeImage(
            40, 40, argb(18, 18, 26),
            listOf(10..12 to argb(240, 240, 240), 22..24 to argb(240, 240, 240))
        )
        val out = ColorSampler.sample(px, 40, 40, 0, 0, 40, 40)!!
        assertTrue(ch(out, 16) in 0..60 && ch(out, 8) in 0..60)
    }

    @Test
    fun twoToneBackgroundPicksTheMajorityColor() {
        // 70% mustard / 30% white diagonal-ish split
        val px = makeImage(50, 40, argb(196, 160, 48))
        for (y in 0 until 40) for (x in 35 until 50) px[y * 50 + x] = argb(250, 250, 250)
        val out = ColorSampler.sample(px, 50, 40, 0, 0, 50, 40)!!
        assertTrue("majority mustard: R high, B low, got R=${ch(out, 16)} B=${ch(out, 0)}",
            ch(out, 16) > 140 && ch(out, 0) < 120)
    }

    @Test
    fun fullyTransparentRegionReturnsNull() {
        val px = makeImage(20, 20, argb(0, 0, 0, 0))
        assertNull(ColorSampler.sample(px, 20, 20, 0, 0, 20, 20))
    }

    @Test
    fun emptyOrInvertedBoxReturnsNull() {
        val px = makeImage(20, 20, argb(255, 255, 255))
        assertNull(ColorSampler.sample(px, 20, 20, 10, 10, 10, 20))
        assertNull(ColorSampler.sample(px, 20, 20, 10, 10, 20, 5))
    }

    @Test
    fun outOfBoundsBoxIsClampedNotCrash() {
        val px = makeImage(20, 20, argb(123, 45, 200))
        val out = ColorSampler.sample(px, 20, 20, -10, -10, 100, 100)!!
        assertTrue(ch(out, 16) in 110..140)
    }

    @Test
    fun textColorContrastsWithSampledBackground() {
        assertEquals(0xFFFFFFFF.toInt(), ColorSampler.bestTextColorOn(0xFF101018.toInt()))
        assertEquals(0xFFFFFFFF.toInt(), ColorSampler.bestTextColorOn(0xFF3A2E1E.toInt()))
        assertEquals(0xFF1B1A24.toInt(), ColorSampler.bestTextColorOn(0xFFFFFFFF.toInt()))
        assertEquals(0xFF1B1A24.toInt(), ColorSampler.bestTextColorOn(0xFFF2E9D8.toInt()))
    }
}
