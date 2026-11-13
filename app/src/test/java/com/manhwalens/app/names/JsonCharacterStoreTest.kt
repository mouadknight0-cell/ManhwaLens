package com.manhwalens.app.names

import com.manhwalens.app.translate.NameScript
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * P1 — durability and the two safety rules of the file store:
 * a locked record survives an automatic write, and a corrupt file degrades to "no names"
 * instead of crashing the translation pipeline.
 *
 * Uses a real temporary file rather than a mock, because atomic rename and the corrupt-file
 * path are exactly the things a mock cannot exercise.
 */
class JsonCharacterStoreTest {

    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun setUp() {
        dir = File.createTempFile("character-memory", "").let { probe ->
            probe.delete()
            probe.mkdirs()
            probe
        }
        file = File(dir, "character_memory.json")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `a new record gets an id and survives a restart`() {
        val store = JsonCharacterStore(file) { 1000L }
        val stored = store.upsert(newRecord("Han Ji-woo", "هان جيو"))

        assertTrue(stored.id.isNotBlank())
        assertEquals(1, store.records("s1").size)

        val reopened = JsonCharacterStore(file)
        val back = reopened.records("s1").single()
        assertEquals("Han Ji-woo", back.originalName)
        assertEquals("هان جيو", back.arabicName)
        assertEquals(NameScript.KOREAN, back.script)
    }

    @Test
    fun `series are isolated from each other`() {
        val store = JsonCharacterStore(file)
        store.upsert(newRecord("Han Ji-woo", "هان جيو"))
        store.upsert(newRecord("Han Ji-woo", "هان جيو", seriesId = "s2"))

        assertEquals(1, store.records("s1").size)
        assertEquals(1, store.records("s2").size)
        assertEquals(listOf("s1", "s2"), store.seriesIds())
        assertNull(store.find("s3", "hanjiwoo"))
    }

    @Test
    fun `a locked record is immune to an automatic write`() {
        val store = JsonCharacterStore(file)
        val record = store.upsert(newRecord("Han Ji-woo", "هان جي وو"))
        store.lock(record.id, "هان جيو", 2000L)

        val ignored = store.upsert(
            newRecord("Han Ji-woo", "خطأ").copy(id = record.id, source = NameSource.PAGE_AI)
        )

        assertEquals("هان جيو", ignored.arabicName)
        assertTrue(ignored.locked)
        assertEquals("هان جيو", store.findById(record.id)?.arabicName)
    }

    @Test
    fun `a lock records the spelling it replaced`() {
        val store = JsonCharacterStore(file)
        val record = store.upsert(newRecord("Han Ji-woo", "هان جي وو"))

        val locked = store.lock(record.id, "هان جيو", 3000L)

        assertEquals("هان جيو", locked?.arabicName)
        assertEquals(listOf("هان جي وو"), locked?.previousArabic)
        assertEquals(NameSource.USER, locked?.source)
    }

    @Test
    fun `unlocking keeps the spelling but allows a later correction`() {
        val store = JsonCharacterStore(file)
        val record = store.upsert(newRecord("Han Ji-woo", "هان جيو"))
        store.lock(record.id, "هان جيو", 1L)

        val unlocked = store.unlock(record.id, 2L)
        assertEquals("هان جيو", unlocked?.arabicName)
        assertFalse(unlocked!!.locked)

        val updated = store.updateArabic(record.id, "هان جيوو", NameSource.USER, 3L)
        assertEquals("هان جيوو", updated?.arabicName)
    }

    @Test
    fun `updateArabic may not touch a locked record`() {
        val store = JsonCharacterStore(file)
        val record = store.upsert(newRecord("Han Ji-woo", "هان جيو"))
        store.lock(record.id, "هان جيو", 1L)

        store.updateArabic(record.id, "خطأ", NameSource.PAGE_AI, 2L)

        assertEquals("هان جيو", store.findById(record.id)?.arabicName)
    }

    @Test
    fun `aliases are searchable and never replace the original`() {
        val store = JsonCharacterStore(file)
        val record = store.upsert(newRecord("Han Ji-woo", "هان جيو"))

        store.addAlias(record.id, "Jiwoo", 5L)
        store.addAlias(record.id, "ji-woo", 6L) // same key, must not duplicate

        val stored = store.findById(record.id)!!
        assertEquals("Han Ji-woo", stored.originalName)
        assertEquals(listOf("Jiwoo"), stored.aliases)
        assertNotNull(store.find("s1", "Jiwoo"))
    }

    @Test
    fun `delete removes one record and deleteSeries removes the series`() {
        val store = JsonCharacterStore(file)
        val a = store.upsert(newRecord("Han Ji-woo", "هان جيو"))
        store.upsert(newRecord("Lan Wangji", "لان وانغجي"))

        assertTrue(store.delete(a.id))
        assertEquals(1, store.records("s1").size)

        assertEquals(1, store.deleteSeries("s1"))
        assertTrue(store.records("s1").isEmpty())
        assertTrue(store.seriesIds().isEmpty())
    }

    @Test
    fun `a record without a usable arabic spelling is not written to the table by accident`() {
        val store = JsonCharacterStore(file)
        store.upsert(newRecord("Wang", "").copy(confidence = 0.3f))

        val stored = store.records("s1").single()
        assertEquals("", stored.arabicName)
        assertFalse(stored.isTrusted)
    }

    @Test
    fun `a corrupt file is set aside and the store starts empty`() {
        file.writeText("{ this is not json")

        val store = JsonCharacterStore(file)

        assertTrue(store.records("s1").isEmpty())
        assertTrue(File(dir, "character_memory.json.corrupt").exists())
    }

    @Test
    fun `a newer schema is kept aside instead of guessed at`() {
        file.writeText("""{"version": 99, "series": {"s1": []}}""")

        val store = JsonCharacterStore(file)

        assertTrue(store.records("s1").isEmpty())
        assertTrue(File(dir, "character_memory.json.newer").exists())
    }

    private fun newRecord(
        original: String,
        arabic: String,
        seriesId: String = "s1"
    ) = CharacterRecord(
        id = "",
        seriesId = seriesId,
        originalName = original,
        normalizedName = NameForm.normalize(original),
        arabicName = arabic,
        script = NameScript.KOREAN,
        confidence = 0.8f,
        source = NameSource.HEURISTIC
    )
}
