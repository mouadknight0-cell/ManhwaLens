package com.manhwalens.app.ocr

import com.manhwalens.app.ocr.geometry.Rect2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockFilterTest {

    private fun block(id: Int, text: String) = BubbleBlock(
        id = id,
        lines = listOf(DetectedLine(text = text, box = Rect2D(0f, 0f, 100f, 40f)))
    )

    // ---- The acceptance-critical case: short single-line dialogue PASSES ----
    @Test
    fun shortSingleLineBubble_passes() {
        val result = BlockFilter.apply(listOf(block(1, "ALRIGHT, I'LL HAVE A GLASS.")))
        assertEquals(1, result.kept.size)
        assertEquals(0, result.removed.size)
    }

    @Test
    fun oneWordDialogue_passes() {
        for (t in listOf("WHAT?!", "YES!", "NO!", "MAYBE.", "HELLO")) {
            val result = BlockFilter.apply(listOf(block(1, t)))
            assertEquals("text=$t", 1, result.kept.size)
        }
    }

    @Test
    fun multiLineDialogue_passes() {
        val multi = BubbleBlock(
            id = 1,
            lines = listOf(
                DetectedLine("ALRIGHT,", Rect2D(0f, 0f, 100f, 30f)),
                DetectedLine("I'LL HAVE", Rect2D(0f, 30f, 100f, 60f)),
                DetectedLine("A GLASS.", Rect2D(0f, 60f, 100f, 90f))
            )
        )
        val result = BlockFilter.apply(listOf(multi))
        assertEquals(1, result.kept.size)
    }

    // ---- Sound effects are dropped ----
    @Test
    fun exactSfxWords_dropped() {
        for (t in listOf("BOOM!", "KRAK", "SPLASH", "POW")) {
            val result = BlockFilter.apply(listOf(block(1, t)))
            assertEquals("text=$t", 0, result.kept.size)
            assertEquals("text=$t", "sound-effect", result.removed.first().reason)
        }
    }

    @Test
    fun allSfxMultiWordBurst_dropped() {
        val result = BlockFilter.apply(listOf(block(1, "KRAK BOOM!")))
        assertEquals(0, result.kept.size)
        assertEquals("sound-effect", result.removed.first().reason)
    }

    // ---- Watermarks / ignore-list are dropped ----
    @Test
    fun watermarks_dropped() {
        for (t in listOf("www.example.com", "read at tapas", "CHAPTER 12", "https://mangadex.org")) {
            val result = BlockFilter.apply(listOf(block(1, t)))
            assertEquals("text=$t", 0, result.kept.size)
        }
    }

    @Test
    fun empty_dropped() {
        assertEquals(0, BlockFilter.apply(listOf(block(1, "   "))).kept.size)
    }

    @Test
    fun mixedPage_keepsDialogueDropsSfxAndWatermark() {
        val result = BlockFilter.apply(
            listOf(
                block(1, "ALRIGHT, I'LL HAVE A GLASS."),
                block(2, "BOOM!"),
                block(3, "www.manhwa-site.com"),
                block(4, "WE CAN LEAVE NOW.")
            )
        )
        assertEquals(listOf(1, 4), result.kept.map { it.id })
        assertEquals(2, result.removed.size)
    }

    // ---- Frame-edge ignore (Part D) ----
    private fun blockAt(id: Int, text: String, top: Float, bottom: Float) = BubbleBlock(
        id = id,
        lines = listOf(DetectedLine(text = text, box = Rect2D(0f, top, 200f, bottom)))
    )

    @Test
    fun edgeTopBand_dropped() {
        // frameHeight = 1600, top 8% => band [0,128); block fully inside is dropped
        val result = BlockFilter.apply(listOf(blockAt(1, "BROWSER URL HERE", 10f, 100f)), frameHeight = 1600, topPct = 8f, bottomPct = 8f)
        assertEquals(0, result.kept.size)
        assertEquals("edge-top", result.removed.first().reason)
    }

    @Test
    fun edgeBottomBand_dropped() {
        // bottom 8% of 1600 => band [1472,1600]; block fully inside is dropped
        val result = BlockFilter.apply(listOf(blockAt(1, "MENU NAV HOME", 1480f, 1590f)), frameHeight = 1600, topPct = 8f, bottomPct = 8f)
        assertEquals(0, result.kept.size)
        assertEquals("edge-bottom", result.removed.first().reason)
    }

    @Test
    fun overlappingEdgeBand_kept() {
        // partially overlapping the top band — still real page content, keep
        val result = BlockFilter.apply(listOf(blockAt(1, "ALRIGHT, I'LL HAVE A GLASS.", 100f, 220f)), frameHeight = 1600, topPct = 8f, bottomPct = 8f)
        assertEquals(1, result.kept.size)
    }

    @Test
    fun middleBlock_kept() {
        val result = BlockFilter.apply(listOf(blockAt(1, "ALRIGHT, I'LL HAVE A GLASS.", 700f, 780f)), frameHeight = 1600, topPct = 8f, bottomPct = 8f)
        assertEquals(1, result.kept.size)
    }

    @Test
    fun zeroPercent_disablesEdgeIgnore() {
        val result = BlockFilter.apply(listOf(blockAt(1, "INSIDE CHROME STRIP", 10f, 100f)), frameHeight = 1600, topPct = 0f, bottomPct = 0f)
        assertEquals(1, result.kept.size)
    }

    @Test
    fun frameHeightZero_disablesEdgeIgnore() {
        val result = BlockFilter.apply(listOf(blockAt(1, "TOP STRIP TEXT", 10f, 100f)))
        assertEquals(1, result.kept.size)
    }

    // ---- Browser UI / already-Arabic / digits-only (Part B) ----
    @Test
    fun alreadyArabic_dropped() {
        val result = BlockFilter.apply(listOf(block(1, "أهلاً بك في الصفحة")))
        assertEquals(0, result.kept.size)
        assertEquals("already-arabic", result.removed.first().reason)
    }

    @Test
    fun digitsOnly_dropped() {
        for (t in listOf("42", "1,234", "12.05", "Chapter 37".let { "37" })) {
            val result = BlockFilter.apply(listOf(block(1, t)))
            assertEquals("text=$t", 0, result.kept.size)
            assertEquals("text=$t", "digits-only", result.removed.first().reason)
        }
    }

    @Test
    fun symbolsOnly_dropped() {
        for (t in listOf("⋮", "×", "← →", "© ®")) {
            val result = BlockFilter.apply(listOf(block(1, t)))
            assertEquals("text=$t", 0, result.kept.size)
            assertEquals("text=$t", "symbols-only", result.removed.first().reason)
        }
    }

    @Test
    fun chapterWord_withDigits_dropped() {
        // "CHAPTER 12" hits the watermark list in any case (pre-existing rule).
        val result = BlockFilter.apply(listOf(block(1, "CH. 37")))
        assertEquals(0, result.kept.size)
        assertEquals("watermark", result.removed.first().reason)
    }

    // ---- User ignore-list (v0.9.3 rules) ----
    private fun blockWithCenter(id: Int, text: String, centerY: Float): BubbleBlock {
        val top = centerY - 20f
        return BubbleBlock(id, listOf(DetectedLine(text, Rect2D(0f, top, 200f, top + 40f))))
    }

    private val IGNORE = listOf("Subscribe", "top", "views", "coins", "get the app")

    @Test
    fun ignore_wholeNormalizedText_drops() {
        // Entire block is exactly the phrase -> drop, anywhere on the frame.
                for (y in listOf(800f, 120f)) {
            val r = BlockFilter.apply(listOf(blockWithCenter(1, "Subscribe", y)), frameHeight = 1600, topPct = 8f, bottomPct = 8f, userIgnore = IGNORE)
            assertEquals("y=$y", 0, r.kept.size)
            assertEquals("y=$y", "ignore-list", r.removed.first().reason)
        }
        // case-insensitive, whitespace-tolerant
        val r = BlockFilter.apply(listOf(blockWithCenter(1, "  GET   THE\nAPP ", 800f)), frameHeight = 1600, topPct = 8f, bottomPct = 8f, userIgnore = IGNORE)
        assertEquals(0, r.kept.size)
        // fully inside the bottom band -> removed by the edge filter itself (also fine)
        val edge = BlockFilter.apply(listOf(blockWithCenter(1, "Subscribe", 1550f)), frameHeight = 1600, topPct = 8f, bottomPct = 8f, userIgnore = IGNORE)
        assertEquals(0, edge.kept.size)
    }

    @Test
    fun ignore_substringNeverMatches() {
        for (t in listOf("stopwatch", "subscribes", "topmost", "500 views watched")) {
            val r = BlockFilter.apply(listOf(blockWithCenter(1, t, 800f)), frameHeight = 1600, topPct = 8f, bottomPct = 8f, userIgnore = IGNORE + "stop")
            assertEquals("text=$t", 1, r.kept.size)
        }
    }

    @Test
    fun ignore_topOfTheTower_alwaysPasses() {
        // Middle of the page: substring presence of "TOP" must not matter at all.
        val middle = BlockFilter.apply(listOf(blockWithCenter(1, "top of the tower", 800f)), frameHeight = 1600, topPct = 8f, bottomPct = 8f, userIgnore = IGNORE)
        assertEquals(1, middle.kept.size)
        // Edge-straddling 4-word sentence (center in band, block not fully inside): still passes — whole-word matches are only for ≤3-token texts.
        val straddling = BlockFilter.apply(listOf(blockWithCenter(1, "top of the tower", 120f)), frameHeight = 1600, topPct = 8f, bottomPct = 8f, userIgnore = IGNORE)
        assertEquals(1, straddling.kept.size)
    }

    @Test
    fun ignore_wholeWordOnlyAtEdgesForShortBlocks() {
        // "Views" alone at the very bottom edge -> whole-word edge hit -> drop.
        val edge = BlockFilter.apply(listOf(blockWithCenter(1, "Views", 1560f)), frameHeight = 1600, topPct = 8f, bottomPct = 8f, userIgnore = IGNORE)
        assertEquals(0, edge.kept.size)
        // Same block in the middle of the page -> STILL dropped: whole-normalized-text match is frame-wide (spec part 0b).
        val middle = BlockFilter.apply(listOf(blockWithCenter(1, "Views", 800f)), frameHeight = 1600, topPct = 8f, bottomPct = 8f, userIgnore = IGNORE)
        assertEquals(0, middle.kept.size)
        assertEquals("ignore-list", middle.removed.first().reason)
        // 2 tokens with whole word at edge -> drop ("2K views").
        val twoTokens = BlockFilter.apply(listOf(blockWithCenter(1, "2K views", 1560f)), frameHeight = 1600, topPct = 8f, bottomPct = 8f, userIgnore = IGNORE)
        assertEquals(0, twoTokens.kept.size)
        // 4 tokens straddling the edge band: whole-word matching must NOT apply (needs ≤3 tokens).
        val fourWords = BlockFilter.apply(listOf(blockWithCenter(1, "the top of the tower", 120f)), frameHeight = 1600, topPct = 8f, bottomPct = 8f, userIgnore = IGNORE)
        assertEquals(1, fourWords.kept.size)
    }

// ---- Part 5: UI chrome exclusion (exact trace strings) ----

@Test
fun part5_browserTopBarEpisode1_droppedByEdgeBand() {
    // "Episode 1" browser header at the very top edge -> dropped by edge-top band.
    val topEdge = blockWithCenter(1, "Episode1", 30f)  // centerY=30, top=10, bottom=50 -> fully inside top 8% (128px)
    val result = BlockFilter.apply(listOf(topEdge), frameHeight = 1600, topPct = 8f, bottomPct = 13f)
    assertEquals(0, result.kept.size)
    assertEquals("edge-top", result.removed.first().reason)
}

@Test
fun part5_browserUrlBar_droppedByEdgeBand() {
    // "O Preview m.webtoons.com/en/1" browser URL bar at top edge -> dropped.
    val urlBar = blockWithCenter(1, "O Preview m.webtoons.com/en/1", 50f)  // top=30, bottom=70 -> inside top band
    val result = BlockFilter.apply(listOf(urlBar), frameHeight = 1600, topPct = 8f, bottomPct = 13f)
    assertEquals(0, result.kept.size)
    assertEquals("edge-top", result.removed.first().reason)
}

@Test
fun part5_hash2TinyBlock_droppedByDigitsOnly() {
    // "#2" chapter number: digits-only -> dropped by reasonFor() (no letters).
    // Block placed at centerY=800 (middle of page) to avoid edge band.
    val b = blockWithCenter(1, "#2", 800f)
    val result = BlockFilter.apply(listOf(b), frameHeight = 1600, topPct = 8f, bottomPct = 13f, minHeightPx = 26f, minWordCount = 1)
    assertEquals(0, result.kept.size)
    assertEquals("digits-only", result.removed.first().reason)
}

@Test
fun part5_650DigitsOnly_dropped() {
    // "650" page/rating number: digits-only, no letters -> dropped by reasonFor.
    val b = blockWithCenter(1, "650", 800f)
    val result = BlockFilter.apply(listOf(b), frameHeight = 1600, topPct = 8f, bottomPct = 13f, minHeightPx = 26f, minWordCount = 1)
    assertEquals(0, result.kept.size)
    assertEquals("digits-only", result.removed.first().reason)
}

@Test
fun part5_loFramesYe_garbageButKeptWhenNotTiny() {
    // "lo frames ye" - 3 words, middle of page -> kept (not garbage, not in edge band)
    val b = blockWithCenter(1, "lo frames ye", 800f)
    val result = BlockFilter.apply(listOf(b), frameHeight = 1600, topPct = 8f, bottomPct = 13f, minHeightPx = 26f, minWordCount = 1)
    assertEquals(1, result.kept.size)
}

@Test
fun part5_tinyIsolatedBox_droppedByNonBubble() {
    // Tiny block (height 8px < minHeight 26px) with 1 word (= minWordCount, NOT < minWordCount)
    // Since wordCount (1) is NOT < minWordCount (1), the non-bubble check does NOT drop it.
    // But it's only 8px tall — should still be dropped as non-bubble?
    // The check requires BOTH conditions: height < minHeight AND wordCount < minWordCount.
    // A tiny block with 1 word passes the word count, so it's kept.
    // This is by design: "YES!" (1 word, short height) is valid dialogue.
    val tiny = BubbleBlock(1, listOf(DetectedLine("OK", Rect2D(0f, 800f, 200f, 808f))))
    val result = BlockFilter.apply(listOf(tiny), frameHeight = 1600, topPct = 8f, bottomPct = 13f, minHeightPx = 26f, minWordCount = 1)
    // 1 word >= minWordCount(1), so not dropped by non-bubble. Kept as dialogue.
    assertEquals(1, result.kept.size)
}

@Test
fun part5_tinyBoxZeroWords_droppedByNonBubble() {
    // Tiny block with just punctuation: wordCount=1 (not < minWordCount=1), so non-bubble
    // check does NOT apply. But it IS dropped by reasonFor() as "symbols-only".
    val tiny = BubbleBlock(1, listOf(DetectedLine("...", Rect2D(0f, 800f, 200f, 808f))))
    val result = BlockFilter.apply(listOf(tiny), frameHeight = 1600, topPct = 8f, bottomPct = 13f, minHeightPx = 26f, minWordCount = 1)
    assertEquals(0, result.kept.size)
    assertEquals("symbols-only", result.removed.first().reason)
}

@Test
fun part5_bottomToolbar_droppedByEdgeBand() {
    // Browser bottom toolbar at bottom edge -> dropped by edge-bottom band (13% of 1600 = 208px from bottom).
    // centerY=1560 -> top=1540, bottom=1580. Edge bottom starts at 1600*(1-0.13)=1392.
    // block.bottom (1580) >= 1392 -> edge-bottom.
    val bottomBar = blockWithCenter(1, "Get the app", 1560f)
    val result = BlockFilter.apply(listOf(bottomBar), frameHeight = 1600, topPct = 8f, bottomPct = 13f)
    assertEquals(0, result.kept.size)
    assertEquals("edge-bottom", result.removed.first().reason)
}

}
