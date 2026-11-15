package com.manhwalens.app.names

import com.manhwalens.app.translate.NameScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 — the resolution ladder and the write rules.
 *
 * The tests below are the acceptance criteria of the phase in executable form:
 *  - a locked name is never displaced by anything automatic;
 *  - an untrusted (transliterated) record never impersonates a confirmed one;
 *  - a name the reader never confirmed is never silently written over an existing record;
 *  - with no series selected nothing is read from, or written to, series memory.
 */
class CharacterResolverTest {

    private val page = listOf("Then Han Ji-woo left the room without a word.")

    @Test
    fun `a locked record supplies the spelling and says so`() {
        val store = FakeStore()
        store.add(record(arabic = "هان جيو", locked = true, source = NameSource.USER))

        val table = CharacterResolver(store).resolve("s1", page, known = emptyMap())
        // The page spelling may be canonicalised ("Han Jiwoo"), so match on the lookup key.
        val han = table.names.first { NameForm.normalize(it.original) == "hanjiwoo" }

        assertEquals("هان جيو", han.arabic)
        assertTrue(han.locked)
        assertEquals(NameSource.USER, han.source)
        assertEquals("locked", han.reason)
        assertFalse(han.persistable)
    }

    @Test
    fun `a locked spelling is applied even when the scanner ignores the token`() {
        val store = FakeStore()
        // "Hope" is an ambiguous English word: the page scanner will not pin it as a name,
        // but the reader locked it, so the record alone must put it in the table.
        store.add(
            record(
                original = "Hope",
                arabic = "هوب",
                locked = true,
                source = NameSource.USER
            )
        )

        val table = CharacterResolver(store)
            .resolve("s1", listOf("I still Hope you come back."), known = emptyMap())

        assertEquals("هوب", table.names.first { NameForm.normalize(it.original) == "hope" }.arabic)
    }

    @Test
    fun `a remembered name survives the page planner's ambiguity gate`() {
        val store = FakeStore()
        // P2-18 stops the planner from OFFERING the bare word "Sun" as a new character.
        // The reader's own series memory is not a guess, so it must still reach the table.
        store.add(record(original = "Sun", arabic = "سون", source = NameSource.USER, confidence = 0.9f))

        val table = CharacterResolver(store)
            .resolve("s1", listOf("The Sun was hot and the road was long."), known = emptyMap())

        val sun = table.names.first { NameForm.normalize(it.original) == "sun" }
        assertEquals("سون", sun.arabic)
        assertEquals(NameSource.USER, sun.source)
        assertEquals("series memory", sun.reason)
    }

    @Test
    fun `an alias finds the record`() {
        val store = FakeStore()
        store.add(
            record(
                arabic = "هان جيو",
                locked = true,
                source = NameSource.USER,
                aliases = listOf("Jiwoo")
            )
        )

        val table = CharacterResolver(store)
            .resolve("s1", listOf("Jiwoo is late."), known = emptyMap())

        assertEquals("هان جيو", table.names.first { NameForm.normalize(it.original) == "hanjiwoo" }.arabic)
    }

    @Test
    fun `an untrusted transliteration in memory never poses as confirmed`() {
        val store = FakeStore()
        store.add(
            record(
                arabic = "زززز",
                confidence = 0.3f,
                source = NameSource.TRANSLITERATION
            )
        )

        val table = CharacterResolver(store).resolve("s1", page, known = emptyMap())

        assertTrue(table.names.none { it.arabic == "زززز" })
        assertTrue(table.names.none { it.locked })
        // Whatever the page resolves to, it is not the remembered guess.
        table.names.forEach { assertFalse(it.reason == "series memory") }
    }

    @Test
    fun `nothing is read or written when no series is selected`() {
        val store = FakeStore()
        store.add(record(arabic = "هان جيو", locked = true, source = NameSource.USER))

        val table = CharacterResolver(store).resolve(null, page, known = emptyMap())

        assertNull(table.seriesId)
        assertEquals(0, store.reads)
        assertEquals(0, CharacterWriter(store).commit(table, now = 1L))
        assertEquals(0, store.writes)
    }

    @Test
    fun `the writer never overwrites a record that already exists`() {
        val store = FakeStore()
        store.add(record(arabic = "هان جي وو", source = NameSource.HEURISTIC, confidence = 0.5f))

        val n = CharacterWriter(store).commit(
            PageNameTable(
                listOf(
                    ResolvedName(
                        original = "Han Ji-woo",
                        arabic = "هان جيو",
                        script = NameScript.KOREAN,
                        confidence = 0.9f,
                        source = NameSource.BUILT_IN,
                        locked = false,
                        recordId = null,
                        persistable = true,
                        reason = "built-in"
                    )
                ),
                seriesId = "s1"
            ),
            now = 10L
        )

        assertEquals(0, n)
        assertEquals("هان جي وو", store.records("s1").single().arabicName)
    }

    @Test
    fun `the writer stores an eligible new name once`() {
        val store = FakeStore()
        val table = PageNameTable(
            listOf(
                ResolvedName(
                    original = "Lan Wangji",
                    arabic = "لان وانغجي",
                    script = NameScript.CHINESE,
                    confidence = 0.9f,
                    source = NameSource.BUILT_IN,
                    locked = false,
                    recordId = null,
                    persistable = true,
                    reason = "built-in"
                )
            ),
            seriesId = "s1"
        )
        val writer = CharacterWriter(store)

        assertEquals(1, writer.commit(table, now = 10L))
        assertEquals(0, writer.commit(table, now = 11L))
        assertEquals(1, store.records("s1").size)
        assertEquals("لان وانغجي", store.records("s1").single().arabicName)
        assertFalse(store.records("s1").single().locked)
    }

    @Test
    fun `a name kept in latin is not written`() {
        val store = FakeStore()
        val table = PageNameTable(
            listOf(
                ResolvedName(
                    original = "Wang",
                    arabic = null,
                    script = NameScript.UNKNOWN,
                    confidence = 0.25f,
                    source = NameSource.HEURISTIC,
                    locked = false,
                    recordId = null,
                    persistable = false,
                    reason = "single ambiguous surname"
                )
            ),
            seriesId = "s1"
        )

        assertEquals(0, CharacterWriter(store).commit(table, now = 1L))
        assertTrue(store.records("s1").isEmpty())
    }

    @Test
    fun `an empty page produces an empty table`() {
        val table = CharacterResolver(FakeStore()).resolve("s1", emptyList(), known = emptyMap())
        assertTrue(table.isEmpty)
    }

    // ------------------------------------------------------------------ fakes

    private fun record(
        original: String = "Han Ji-woo",
        arabic: String,
        locked: Boolean = false,
        source: NameSource = NameSource.HEURISTIC,
        confidence: Float = 0.5f,
        aliases: List<String> = emptyList()
    ) = CharacterRecord(
        id = "s1-${original.hashCode()}",
        seriesId = "s1",
        originalName = original,
        normalizedName = NameForm.normalize(original),
        arabicName = arabic,
        script = NameScript.KOREAN,
        aliases = aliases,
        confidence = confidence,
        source = source,
        locked = locked
    )

    /** In-memory [CharacterStore] so the resolver stays testable on the JVM. */
    private class FakeStore : CharacterStore {

        private val items = ArrayList<CharacterRecord>()
        var reads = 0
            private set
        var writes = 0
            private set

        fun add(record: CharacterRecord) {
            items += record
        }

        override fun records(seriesId: String): List<CharacterRecord> {
            reads++
            return items.filter { it.seriesId == seriesId }
        }

        override fun find(seriesId: String, normalizedName: String): CharacterRecord? {
            val key = NameForm.normalize(normalizedName)
            return items.firstOrNull { it.seriesId == seriesId && it.normalizedName == key }
        }

        override fun findById(id: String): CharacterRecord? = items.firstOrNull { it.id == id }

        override fun upsert(record: CharacterRecord): CharacterRecord {
            writes++
            val index = items.indexOfFirst {
                it.seriesId == record.seriesId && it.normalizedName == record.normalizedName
            }
            if (index < 0) {
                items += record.copy(id = record.id.ifBlank { "new-${items.size}" })
                return items.last()
            }
            val existing = items[index]
            if (existing.locked && !record.locked) return existing
            items[index] = existing.copy(
                arabicName = record.arabicName,
                confidence = maxOf(existing.confidence, record.confidence),
                source = record.source
            )
            return items[index]
        }

        override fun lock(id: String, arabic: String, now: Long): CharacterRecord? {
            writes++
            val index = items.indexOfFirst { it.id == id }
            if (index < 0) return null
            items[index] = items[index]
                .withArabic(arabic, NameSource.USER, 1f, now)
                .copy(locked = true, updatedAt = now)
            return items[index]
        }

        override fun unlock(id: String, now: Long): CharacterRecord? {
            writes++
            val index = items.indexOfFirst { it.id == id }
            if (index < 0) return null
            items[index] = items[index].copy(locked = false, updatedAt = now)
            return items[index]
        }

        override fun updateArabic(
            id: String,
            arabic: String,
            source: NameSource,
            now: Long
        ): CharacterRecord? {
            writes++
            val index = items.indexOfFirst { it.id == id }
            if (index < 0) return null
            items[index] = items[index].withArabic(arabic, source, 0.9f, now)
            return items[index]
        }

        override fun addAlias(id: String, alias: String, now: Long): CharacterRecord? {
            writes++
            val index = items.indexOfFirst { it.id == id }
            if (index < 0) return null
            items[index] = items[index].copy(
                aliases = (items[index].aliases + alias).distinct(),
                updatedAt = now
            )
            return items[index]
        }

        override fun delete(id: String): Boolean {
            writes++
            return items.removeAll { it.id == id }
        }

        override fun deleteSeries(seriesId: String): Int {
            writes++
            val before = items.size
            items.removeAll { it.seriesId == seriesId }
            return before - items.size
        }

        override fun seriesIds(): List<String> = items.map { it.seriesId }.distinct()
    }
}
