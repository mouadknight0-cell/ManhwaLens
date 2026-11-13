package com.manhwalens.app.names

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P1 — the series catalogue on top of [SeriesPrefs].
 *
 * The catalogue is what makes memory **series-scoped**: a reader package is not a story, so the
 * reader says which story they are curating and every record is filed under that id. These tests
 * use an in-memory [KeyValueStore] — no Android, no SharedPreferences.
 */
class SeriesCatalogTest {

    private val kv = MapStore()
    private var clock = 100L
    private val catalog = SeriesCatalog(kv) { clock }

    @Test
    fun `a fresh install has no series and an unassigned session`() {
        assertTrue(catalog.all().isEmpty())
        assertEquals("", catalog.activeId())
        assertNull(catalog.active())
    }

    @Test
    fun `create files the series and keeps it across instances`() {
        val created = catalog.create("Solo Leveling")

        assertEquals("Solo Leveling", created.title)
        assertEquals(100L, created.createdAt)
        assertEquals(1, catalog.all().size)

        val reopened = SeriesCatalog(kv)
        assertEquals(1, reopened.all().size)
        assertEquals("Solo Leveling", reopened.all().single().title)
    }

    @Test
    fun `creating the same title twice keeps one series`() {
        val first = catalog.create("Solo Leveling")
        val second = catalog.create("  solo   leveling ")

        assertEquals(first.id, second.id)
        assertEquals(1, catalog.all().size)
    }

    @Test
    fun `different titles get different ids`() {
        val a = catalog.create("Solo Leveling")
        val b = catalog.create("Tower of God")

        assertNotEquals(a.id, b.id)
        assertEquals(2, catalog.all().size)
    }

    @Test
    fun `selecting a series scopes the session`() {
        val series = catalog.create("Solo Leveling")
        catalog.select(series)

        assertEquals(series.id, catalog.activeId())
        assertEquals(series.id, catalog.active()?.id)
    }

    @Test
    fun `selecting null leaves the session unassigned`() {
        val series = catalog.create("Solo Leveling")
        catalog.select(series)
        catalog.select(null)

        assertEquals("", catalog.activeId())
        assertNull(catalog.active())
    }

    @Test
    fun `a dangling selection reads as unassigned instead of throwing`() {
        catalog.select(Series(id = "gone-1234", title = "Deleted", createdAt = 1L))

        assertNull(catalog.active())
        assertTrue(catalog.all().isEmpty())
    }

    @Test
    fun `rename keeps the id and the selection`() {
        val series = catalog.create("Solo Leveling")
        catalog.select(series)

        val renamed = catalog.rename(series.id, "Solo Leveling: Ragnarok")

        assertEquals(series.id, renamed?.id)
        assertEquals("Solo Leveling: Ragnarok", catalog.active()?.title)
    }

    @Test
    fun `rename of an unknown id changes nothing`() {
        catalog.create("Solo Leveling")

        assertNull(catalog.rename("nope-0000", "Whatever"))
        assertEquals("Solo Leveling", catalog.all().single().title)
    }

    @Test
    fun `delete clears the selection when the active series goes away`() {
        val keep = catalog.create("Solo Leveling")
        val drop = catalog.create("Tower of God")
        catalog.select(keep)
        catalog.select(drop)

        assertTrue(catalog.delete(drop.id))
        assertEquals(keep.id, catalog.all().single().id)
        assertEquals("", catalog.activeId())
    }

    @Test
    fun `delete of an unknown id reports false`() {
        catalog.create("Solo Leveling")
        assertFalse(catalog.delete("nope-0000"))
    }

    @Test
    fun `an id is stable for the same title and different for another`() {
        val a = SeriesCatalog.idFor("Solo Leveling")
        val b = SeriesCatalog.idFor("solo leveling")
        val c = SeriesCatalog.idFor("Tower of God")

        assertEquals(a, b)
        assertNotEquals(a, c)
        assertTrue(a.startsWith("solo"))
    }

    /** Minimal in-memory [KeyValueStore]; SharedPreferences supplies the real one. */
    private class MapStore : KeyValueStore {
        private val map = HashMap<String, Any>()
        override fun getString(key: String, def: String): String = map[key] as? String ?: def
        override fun putString(key: String, value: String) {
            map[key] = value
        }

        override fun getLong(key: String, def: Long): Long = map[key] as? Long ?: def
        override fun putLong(key: String, value: Long) {
            map[key] = value
        }
    }
}
