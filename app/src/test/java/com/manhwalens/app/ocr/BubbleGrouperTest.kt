package com.manhwalens.app.ocr

import com.manhwalens.app.ocr.geometry.Rect2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleGrouperTest {

    private fun line(
        text: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        confidence: Float = 0.9f
    ) = DetectedLine(text, Rect2D(left, top, right, bottom), confidence)

    @Test
    fun `three stacked lines of one bubble form a single block`() {
        val input = listOf(
            line("I WON'T", 100f, 400f, 380f, 444f),
            line("LOSE", 150f, 452f, 330f, 496f),
            line("TODAY!", 95f, 504f, 385f, 548f)
        )
        val blocks = BubbleGrouper.group(input)
        assertEquals(1, blocks.size)
        assertEquals(listOf("I WON'T", "LOSE", "TODAY!"), blocks[0].lines.map { it.text })
        assertEquals(3, blocks[0].lines.size)
    }

    @Test
    fun `two side-by-side bubbles stay split despite interleaved tops`() {
        val input = listOf(
            line("LEFT ONE", 40f, 100f, 320f, 148f),
            line("LEFT TWO", 70f, 156f, 290f, 204f),
            line("RIGHT ONE", 880f, 120f, 1200f, 168f),
            line("LEFT THREE", 55f, 212f, 305f, 260f),
            line("RIGHT TWO", 910f, 176f, 1170f, 224f),
            line("RIGHT THREE", 895f, 232f, 1185f, 280f)
        )
        val blocks = BubbleGrouper.group(input)
        assertEquals(2, blocks.size)
        val left = blocks.first { it.bounds.centerX < 600f }
        val right = blocks.first { it.bounds.centerX >= 600f }
        assertEquals(setOf("LEFT ONE", "LEFT TWO", "LEFT THREE"), left.lines.map { it.text }.toSet())
        assertEquals(setOf("RIGHT ONE", "RIGHT TWO", "RIGHT THREE"), right.lines.map { it.text }.toSet())
    }

    @Test
    fun `small vertical gap within bubble merges`() {
        val input = listOf(
            line("THIS IS", 120f, 100f, 420f, 144f),
            line("ONE BUBBLE", 120f, 166f, 420f, 210f) // gap = 22 = 0.5x line height
        )
        assertEquals(1, BubbleGrouper.group(input).size)
    }

    @Test
    fun `large vertical gap between spoken lines splits blocks`() {
        val input = listOf(
            line("BUBBLE ONE", 120f, 100f, 420f, 144f),
            line("BUBBLE TWO", 120f, 204f, 420f, 248f) // gap = 60 = 1.36x > 0.9 ratio
        )
        val blocks = BubbleGrouper.group(input)
        assertEquals(2, blocks.size)
        assertEquals(listOf("BUBBLE ONE"), blocks[0].lines.map { it.text })
        assertEquals(listOf("BUBBLE TWO"), blocks[1].lines.map { it.text })
    }

    @Test
    fun `sfx burst beside dialogue does not merge`() {
        val input = listOf(
            line("KRAKOOOM", 30f, 40f, 1060f, 190f),   // h=150 SFX
            line("HI!", 330f, 150f, 520f, 194f),       // h=44 dialogue overlapping SFX box vertically
            line("HOW ARE YOU?", 180f, 202f, 780f, 246f)
        )
        val blocks = BubbleGrouper.group(input)
        assertEquals(2, blocks.size)
        val sfx = blocks.first { it.lines.any { l -> l.text == "KRAKOOOM" } }
        val dialogue = blocks.first { it.lines.any { l -> l.text == "HI!" } }
        assertEquals(1, sfx.lines.size)
        assertEquals(listOf("HI!", "HOW ARE YOU?"), dialogue.lines.map { it.text })
    }

    @Test
    fun `short line above long line inside bubble merges`() {
        val input = listOf(
            line("HI!", 300f, 300f, 420f, 344f),
            line("HOW ARE YOU?", 60f, 352f, 660f, 396f)
        )
        assertEquals(1, BubbleGrouper.group(input).size)
    }

    @Test
    fun `dialogue words with mixed emphasis sizes still merge`() {
        val input = listOf(
            line("STOP!", 250f, 100f, 520f, 158f),     // h=58 emphasis
            line("right there", 230f, 166f, 540f, 210f) // h=44 normal, ratio 0.76
        )
        assertEquals(1, BubbleGrouper.group(input).size)
    }

    @Test
    fun `empty input yields no blocks`() {
        assertTrue(BubbleGrouper.group(emptyList()).isEmpty())
    }

    @Test
    fun `single line yields one block`() {
        val blocks = BubbleGrouper.group(listOf(line("SOLO", 10f, 10f, 200f, 54f)))
        assertEquals(1, blocks.size)
        assertEquals("SOLO", blocks[0].text)
    }

    @Test
    fun `side-by-side columns with tiny horizontal overlap do not merge`() {
        val input = listOf(
            line("LEFT COLUMN", 40f, 100f, 380f, 144f),
            line("RIGHT COLUMN", 370f, 152f, 760f, 196f) // overlap 10px of min width 340 => 0.03 < 0.2
        )
        assertEquals(2, BubbleGrouper.group(input).size)
    }
}
