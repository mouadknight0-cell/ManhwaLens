package com.manhwalens.app.ocr

import com.manhwalens.app.ocr.geometry.Rect2D
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrGarbageClassifierTest {

    private fun rect(l: Float, t: Float, r: Float, b: Float) = Rect2D(l, t, r, b)

    private fun classifier(owned: Set<String> = setOf("watching", "scanning", "مشاهدة"),
                           now: Long = 10_000L) =
        OcrGarbageClassifier(owned) { now }

    @Test
    fun patchOverlap_dropsBlock() {
        val c = classifier()
        // Patch was at (20,20)-(80,80); block sits right over it.
        val v = c.classify("NHRAIP", rect(30f, 30f, 70f, 70f),
            patchRects = listOf(rect(20f, 20f, 80f, 80f)), ownedRects = emptyList())
        assertFalse(v.keep)
        assertEquals(GarbageReason.PATCH_OVERLAP, v.reason)
    }

    @Test
    fun justRemovedPatchStillCounts() {
        val c = classifier(now = 10_000L)
        // Patch removed 0.5 s ago → still within the 1 s grace window.
        val v = c.classify("garbage", rect(30f, 30f, 70f, 70f),
            patchRects = listOf(rect(20f, 20f, 80f, 80f)), ownedRects = emptyList())
        assertFalse(v.keep)
    }

    @Test
    fun ownWindowOverlap_dropsBlock() {
        val c = classifier()
        val v = c.classify("Watching", rect(0f, 0f, 40f, 40f),
            patchRects = emptyList(), ownedRects = listOf(rect(0f, 0f, 60f, 60f)))
        assertFalse(v.keep)
        assertEquals(GarbageReason.OWN_WINDOW, v.reason)
    }

    @Test
    fun ownString_dropsBlock() {
        val c = classifier()
        val v = c.classify("Watching", rect(100f, 100f, 200f, 200f),
            patchRects = emptyList(), ownedRects = emptyList())
        assertFalse(v.keep)
        assertEquals(GarbageReason.OWN_STRING, v.reason)

        val cAr = classifier(owned = setOf("مشاهدة"), now = 0L)
        assertFalse(cAr.classify("مشاهدة", rect(0f, 0f, 10f, 10f),
            emptyList(), emptyList()).keep)
    }

    @Test
    fun recognizableAndroidStatusAndNotificationTextIsDropped() {
        val c = classifier()
        val systemUi = listOf(
            "7:57 Sun, Nov 15 Djezzy | mobilis",
            "Today: 309.8 MB",
            "This month: 12.82 GB",
            "ManhwaLens • now Capture ready · tap the lens to grab the screen",
            "ManhwaLens is displaying over other apps. If you don't want ManhwaLens..."
        )
        for (text in systemUi) {
            val verdict = c.classify(text, rect(100f, 100f, 500f, 160f), emptyList(), emptyList())
            assertFalse("$text must be classified as system UI", verdict.keep)
            assertEquals(GarbageReason.SYSTEM_UI, verdict.reason)
        }
    }

    @Test
    fun ordinaryDialogueMentioningTodayOrManhwaLensIsKept() {
        val c = classifier()
        val dialogue = listOf(
            "Today is the day we defeat the boss.",
            "ManhwaLens was the name written on the screen.",
            "The mobilis gate is still open."
        )
        for (text in dialogue) {
            assertTrue(
                "$text must not be mistaken for Android UI",
                c.classify(text, rect(100f, 100f, 500f, 160f), emptyList(), emptyList()).keep
            )
        }
    }

    @Test
    fun consonantHeavyAllCaps_dropsBlock() {
        val c = classifier()
        // Pure-consonant all-caps noise (10/10 consonants, 6+ letters) → OCR garbage.
        val v = c.classify("NPHGRK ZXCV", rect(100f, 100f, 200f, 200f),
            emptyList(), emptyList())
        assertFalse(v.keep)
        assertEquals(GarbageReason.OCR_GARBAGE, v.reason)
    }

    @Test
    fun realDialogue_kept() {
        val c = classifier()
        for (t in listOf("STOP!", "YES!", "NO!", "What?!", "Episode 1", "Hello world")) {
            val v = c.classify(t, rect(100f, 100f, 200f, 200f), emptyList(), emptyList())
            assertTrue("$t should be kept", v.keep)
        }
    }

    @Test
    fun shortAllCapsKept() {
        val c = classifier()
        // "TV" — all caps but only 2 letters (< min 5) → kept.
        assertTrue(c.classify("TV", rect(0f, 0f, 10f, 10f), emptyList(), emptyList()).keep)
    }

    @Test
    fun mixedCaseNotGarbage() {
        val c = classifier()
        // "Nhraip" — not all caps → kept (even if consonant-heavy).
        assertTrue(c.classify("Nhraip", rect(0f, 0f, 10f, 10f), emptyList(), emptyList()).keep)
    }

    @Test
    fun validConsonantHeavyEnglishWordsAreNotGarbage() {
        val c = classifier()
        for (text in listOf("STRENGTH", "RHYTHM", "STRENGTHS")) {
            assertTrue(
                "$text is a valid word and must survive the OCR garbage gate",
                c.classify(text, rect(0f, 0f, 10f, 10f), emptyList(), emptyList()).keep
            )
        }
    }

    @Test
    fun emptyText_kept() {
        val c = classifier()
        assertTrue(c.classify("", rect(0f, 0f, 10f, 10f), emptyList(), emptyList()).keep)
    }
}
