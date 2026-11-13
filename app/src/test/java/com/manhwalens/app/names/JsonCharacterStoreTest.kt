package com.manhwalens.app.names

import com.manhwalens.app.translate.NameScript
import org.junit.After
import org.junit.Assert.assertArrayEquals
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
    fun `a successful save leaves no staging file behind`() {
        val store = JsonCharacterStore(file)
        store.upsert(newRecord("Han Ji-woo", "هان جيو"))

        assertTrue(file.exists())
        assertFalse(File(dir, "character_memory.json.tmp").exists())
    }

    @Test
    fun `a failed write leaves the stored records readable`() {
        val store = JsonCharacterStore(file)
        store.upsert(newRecord("Han Ji-woo", "هان جيو"))

        // Block the staging path so startWrite cannot even open its file: a directory with a
        // file in it can be neither opened for writing nor deleted.
        val staging = File(dir, "character_memory.json.tmp")
        assertTrue(staging.mkdirs())
        File(staging, "keep").writeText("x")

        store.upsert(newRecord("Lan Wangji", "لان وانغجي")) // in memory only: the save failed

        // The stored file is untouched and still parses — no truncation, no half-written JSON.
        val reopened = JsonCharacterStore(file)
        val kept = reopened.records("s1")
        assertEquals(1, kept.size)
        assertEquals("Han Ji-woo", kept.single().originalName)
    }

    @Test
    fun `a successful save after a failed one persists the full state`() {
        val store = JsonCharacterStore(file)
        store.upsert(newRecord("Han Ji-woo", "هان جيو"))

        val staging = File(dir, "character_memory.json.tmp")
        assertTrue(staging.mkdirs())
        File(staging, "keep").writeText("x")
        store.upsert(newRecord("Lan Wangji", "لان وانغجي")) // not persisted

        // The obstacle disappears (crash cleanup, user freed space, …): the next save repairs
        // everything, because the in-memory index is the source of truth.
        File(staging, "keep").delete()
        assertTrue(staging.delete())
        store.upsert(newRecord("Wei Wuxian", "وي ووشيان"))

        val reopened = JsonCharacterStore(file)
        assertEquals(3, reopened.records("s1").size)
    }

    @Test
    fun `a complete copy left by an interrupted write is adopted`() {
        val store = JsonCharacterStore(file)
        store.upsert(newRecord("Han Ji-woo", "هان جيو"))

        // Crash between startWrite and finishWrite: the live file never appeared, but the
        // fsynced copy is complete.
        val staging = File(dir, "character_memory.json.tmp")
        assertTrue(file.renameTo(staging))
        assertFalse(file.exists())

        val reopened = JsonCharacterStore(file)

        assertEquals("Han Ji-woo", reopened.records("s1").single().originalName)
        assertTrue(file.exists())
        assertFalse(staging.exists())
    }

    @Test
    fun `a truncated copy left by an interrupted write is never adopted`() {
        File(dir, "character_memory.json.tmp").writeText("""{"version":1,"series":{"s1":[{"id":"s1-""")

        val store = JsonCharacterStore(file)

        assertTrue(store.records("s1").isEmpty())
        assertFalse(file.exists()) // nothing was promoted into the live file
    }

    @Test
    fun `a leftover copy never shadows a readable file`() {
        val store = JsonCharacterStore(file)
        store.upsert(newRecord("Han Ji-woo", "هان جيو"))

        val other = newRecord("Lan Wangji", "لان وانغجي").copy(id = "s1-other")
        File(dir, "character_memory.json.tmp")
            .writeText("""{"version":1,"series":{"s1":[${other.toJson()}]}}""")

        val reopened = JsonCharacterStore(file)
        val records = reopened.records("s1")

        assertEquals(1, records.size)
        assertEquals("Han Ji-woo", records.single().originalName)
    }

    @Test
    fun `a refused promotion never writes into the live file`() {
        // Complete copy from an interrupted save, no live file, and a filesystem that refuses to
        // promote it: the old fallback copied into the live path, which could be torn by a kill.
        val staging = File(dir, "character_memory.json.tmp")
        writeDocument(staging, newRecord("Han Ji-woo", "هان جيو").copy(id = "s1-a"))
        val stagingBefore = staging.readBytes()
        val warnings = ArrayList<String>()

        val store = JsonCharacterStore(
            file,
            atomicMove = { _, _ -> false },
            onWarning = { warnings += it }
        )
        val records = store.records("s1")

        // The records are still available — read from the copy in place, never duplicated by a
        // non-atomic write into the live file.
        assertEquals(1, records.size)
        assertEquals("Han Ji-woo", records.single().originalName)
        assertFalse(file.exists())
        assertArrayEquals(stagingBefore, staging.readBytes())
        assertEquals(1, warnings.size)
        assertTrue(warnings.single().contains("could not promote"))
    }

    @Test
    fun `a refused promotion keeps the complete copy for a later attempt`() {
        val staging = File(dir, "character_memory.json.tmp")
        writeDocument(
            staging,
            newRecord("Han Ji-woo", "هان جيو").copy(id = "s1-a"),
            newRecord("Lan Wangji", "لان وانغجي").copy(id = "s1-b")
        )

        val blocked = JsonCharacterStore(file, atomicMove = { _, _ -> false })
        assertEquals(2, blocked.records("s1").size)
        assertTrue(staging.exists())

        // The obstacle is gone (a later process, a different filesystem state): the copy is
        // promoted and the complete record set is restored.
        val reopened = JsonCharacterStore(file)
        val restored = reopened.records("s1").map { it.originalName }.sorted()

        assertEquals(listOf("Han Ji-woo", "Lan Wangji"), restored)
        assertFalse(staging.exists())
        assertTrue(file.exists())
    }

    @Test
    fun `a refused promotion leaves a valid live file byte-for-byte unchanged`() {
        val store = JsonCharacterStore(file)
        store.upsert(newRecord("Han Ji-woo", "هان جيو"))
        val liveBefore = file.readBytes()
        // A stale copy from an unrelated failure must not be promoted over readable data.
        val staging = File(dir, "character_memory.json.tmp")
        writeDocument(staging, newRecord("Lan Wangji", "لان وانغجي").copy(id = "s1-other"))

        val reopened = JsonCharacterStore(file, atomicMove = { _, _ -> false })

        assertEquals("Han Ji-woo", reopened.records("s1").single().originalName)
        assertArrayEquals(liveBefore, file.readBytes())
    }

    @Test
    fun `a save after a refused promotion keeps the full state in the copy`() {
        val staging = File(dir, "character_memory.json.tmp")
        writeDocument(
            staging,
            newRecord("Han Ji-woo", "هان جيو").copy(id = "s1-a"),
            newRecord("Lan Wangji", "لان وانغجي").copy(id = "s1-b")
        )

        val blocked = JsonCharacterStore(file, atomicMove = { _, _ -> false })
        assertEquals(2, blocked.records("s1").size)
        // Loading from the copy means a mutation writes the complete state back, not an
        // empty store over the only surviving data.
        blocked.upsert(newRecord("Wei Wuxian", "وي ووشيان"))

        val reopened = JsonCharacterStore(file)
        assertEquals(3, reopened.records("s1").size)
    }

    @Test
    fun `a truncated copy is never promoted even when the live file is missing`() {
        File(dir, "character_memory.json.tmp")
            .writeText("""{"version":1,"series":{"s1":[{"id":"s1-""")

        val store = JsonCharacterStore(file, atomicMove = { _, _ -> false })

        assertTrue(store.records("s1").isEmpty())
        assertFalse(file.exists())
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

    /** Serialize records exactly like the store does, for staging-copy fixtures. */
    private fun writeDocument(target: File, vararg records: CharacterRecord) {
        val body = records.joinToString(",") { it.toJson().toString() }
        target.writeText("""{"version":1,"series":{"s1":[$body]}}""")
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
