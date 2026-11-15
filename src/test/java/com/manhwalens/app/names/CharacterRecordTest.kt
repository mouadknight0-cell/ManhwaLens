package com.manhwalens.app.names

import com.manhwalens.app.translate.NameScript
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 — the stored fact itself.
 *
 * The two properties that matter most here: a transliteration can never look "confirmed"
 * ([CharacterRecord.isTrusted]), and a correction keeps the spelling it replaced
 * ([CharacterRecord.previousArabic]) so already-cached translations can be repaired.
 */
class CharacterRecordTest {

    @Test
    fun `json round trip preserves every field`() {
        val record = CharacterRecord(
            id = "s1-7",
            seriesId = "s1",
            originalName = "Han Ji-woo",
            normalizedName = "hanjiwoo",
            arabicName = "هان جيو",
            script = NameScript.KOREAN,
            aliases = listOf("Jiwoo"),
            confidence = 0.9f,
            source = NameSource.USER,
            locked = true,
            createdAt = 100L,
            updatedAt = 200L,
            previousArabic = listOf("هان جي وو"),
            occurrences = 3
        )

        val back = CharacterRecord.fromJson(record.toJson())

        assertEquals(record, back)
    }

    @Test
    fun `fromJson tolerates a partial object`() {
        val minimal = JSONObject()
            .put("id", "s1-1")
            .put("seriesId", "s1")
            .put("originalName", "Wei Wuxian")

        val record = CharacterRecord.fromJson(minimal)

        assertEquals("weiwuxian", record?.normalizedName)
        assertEquals(NameScript.UNKNOWN, record?.script)
        assertEquals(NameSource.HEURISTIC, record?.source)
        assertFalse(record!!.locked)
    }

    @Test
    fun `fromJson returns null instead of throwing on unusable input`() {
        assertNull(CharacterRecord.fromJson(JSONObject()))
        assertNull(CharacterRecord.fromJson(JSONObject().put("id", "x")))
        // An unknown enum name must not lose the record.
        val weird = JSONObject()
            .put("id", "s1-1")
            .put("seriesId", "s1")
            .put("originalName", "Lan Wangji")
            .put("source", "MAGIC")
            .put("script", "ELVISH")
        val record = CharacterRecord.fromJson(weird)
        assertEquals(NameSource.HEURISTIC, record?.source)
        assertEquals(NameScript.UNKNOWN, record?.script)
    }

    @Test
    fun `transliteration is never trusted while a model or the reader is`() {
        assertFalse(record(source = NameSource.TRANSLITERATION, confidence = 0.59f).isTrusted)
        assertTrue(record(source = NameSource.PAGE_AI, confidence = 0.8f).isTrusted)
        assertTrue(record(source = NameSource.USER, confidence = 0.1f).isTrusted)
        assertTrue(record(source = NameSource.BUILT_IN, confidence = 0.1f).isTrusted)
        assertTrue(record(source = NameSource.HEURISTIC, confidence = 0.1f, locked = true).isTrusted)
    }

    @Test
    fun `a correction keeps the spelling it replaced`() {
        val first = record(arabic = "هان جي وو")
        val corrected = first.withArabic("هان جيو", NameSource.USER, 1f, 300L)

        assertEquals("هان جيو", corrected.arabicName)
        assertEquals(listOf("هان جي وو"), corrected.previousArabic)
        assertEquals(300L, corrected.updatedAt)
    }

    @Test
    fun `previous spellings are capped`() {
        var current = record(arabic = "a0")
        repeat(6) { step ->
            current = current.withArabic("ar$step", NameSource.USER, 1f, step.toLong())
        }

        assertTrue(current.previousArabic.size <= CharacterRecord.MAX_PREVIOUS)
        assertEquals(CharacterRecord.MAX_PREVIOUS, current.previousArabic.size)
    }

    @Test
    fun `an unchanged spelling does not pollute the history`() {
        val first = record(arabic = "هان جيو")
        val same = first.withArabic("هان جيو", NameSource.USER, 1f, 5L)
        assertTrue(same.previousArabic.isEmpty())
    }

    private fun record(
        arabic: String = "هان جيو",
        source: NameSource = NameSource.HEURISTIC,
        confidence: Float = 0.5f,
        locked: Boolean = false
    ) = CharacterRecord(
        id = "s1-1",
        seriesId = "s1",
        originalName = "Han Ji-woo",
        normalizedName = "hanjiwoo",
        arabicName = arabic,
        script = NameScript.KOREAN,
        confidence = confidence,
        source = source,
        locked = locked
    )
}
