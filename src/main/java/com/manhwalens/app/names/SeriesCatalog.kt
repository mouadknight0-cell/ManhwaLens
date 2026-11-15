package com.manhwalens.app.names

import org.json.JSONArray
import org.json.JSONObject

/**
 * Minimal key/value seam so the series catalogue is pure Kotlin and JVM-testable.
 * The Android implementation is [com.manhwalens.app.data.SeriesPrefs] (SharedPreferences).
 */
interface KeyValueStore {
    fun getString(key: String, def: String = ""): String
    fun putString(key: String, value: String)
    fun getLong(key: String, def: Long = 0L): Long
    fun putLong(key: String, value: Long)
}

/** One series the reader has curated. [id] is stable and unrelated to any app package. */
data class Series(val id: String, val title: String, val createdAt: Long)

/**
 * The series catalogue (P1): which series the reader is currently translating.
 *
 * WHY THIS EXISTS: a reader application package is NOT a series. One manhwa reader can host
 * hundreds of titles, so binding names to `com.example.reader` would silently mix every story
 * the reader opens. P1 therefore uses an **explicit** series the reader picks or creates; the
 * catalogue never derives a series from the foreground package.
 *
 * When nothing is selected ([active] == null) the session is *unassigned*: names are still
 * resolved for the current page (existing behaviour) but **nothing is persisted**, so a
 * mapping can never be attributed to a story the reader did not choose.
 *
 * Stored as one JSON array under a single key plus the active id, so a corrupt catalogue can
 * be discarded without touching the reader's glossary, settings or character records.
 */
class SeriesCatalog(
    private val kv: KeyValueStore,
    private val clock: () -> Long = System::currentTimeMillis
) {

    /** Every series, creation order. Empty on corruption — never throws. */
    fun all(): List<Series> {
        val raw = kv.getString(KEY_CATALOG, "")
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            val out = ArrayList<Series>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id", "")
                val title = o.optString("title", "")
                if (id.isBlank() || title.isBlank()) continue
                out += Series(id, title, o.optLong("createdAt", 0L))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Id of the selected series, or null when the session is unassigned. */
    fun activeId(): String = kv.getString(KEY_ACTIVE, "").trim()

    /** The selected series, or null. A dangling id reads as unassigned rather than throwing. */
    fun active(): Series? {
        val id = activeId()
        if (id.isBlank()) return null
        return all().firstOrNull { it.id == id }
    }

    /** Select [series], or null to leave the session unassigned (nothing is persisted). */
    fun select(series: Series?) {
        kv.putString(KEY_ACTIVE, series?.id ?: "")
    }

    /**
     * Create a series for [title]. An existing title (case/space insensitive) is returned
     * instead of creating a second database for the same story.
     */
    fun create(title: String): Series {
        val clean = title.trim().replace(Regex("\\s+"), " ").take(MAX_TITLE)
        if (clean.isEmpty()) throw IllegalArgumentException("series title must not be empty")
        all().firstOrNull { it.title.equals(clean, ignoreCase = true) }?.let { return it }
        val now = clock()
        val series = Series(idFor(clean, all()), clean, now)
        val list = all() + series
        kv.putString(KEY_CATALOG, serialize(list))
        return series
    }

    fun rename(seriesId: String, title: String): Series? {
        val clean = title.trim().replace(Regex("\\s+"), " ").take(MAX_TITLE)
        if (clean.isEmpty()) return null
        val list = all()
        val index = list.indexOfFirst { it.id == seriesId }
        if (index < 0) return null
        val updated = list[index].copy(title = clean)
        val newList = list.toMutableList().also { it[index] = updated }
        kv.putString(KEY_CATALOG, serialize(newList))
        return updated
    }

    /**
     * Remove a series from the catalogue. The caller deletes its character records
     * ([CharacterStore.deleteSeries]) so no orphan mapping can ever be re-attached to a
     * different story. Selecting the deleted series clears the selection.
     */
    fun delete(seriesId: String): Boolean {
        val list = all()
        val remaining = list.filterNot { it.id == seriesId }
        if (remaining.size == list.size) return false
        kv.putString(KEY_CATALOG, serialize(remaining))
        if (activeId() == seriesId) select(null)
        return true
    }

    private fun serialize(list: List<Series>): String {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("createdAt", s.createdAt)
            )
        }
        return arr.toString()
    }

    companion object {
        const val KEY_CATALOG = "series_catalog_v1"
        const val KEY_ACTIVE = "active_series_v1"
        private const val MAX_TITLE = 80

        /** Readable, stable id: a slug plus a checksum, so ids survive restarts. */
        fun idFor(title: String, existing: List<Series> = emptyList()): String {
            val slug = title.lowercase()
                .map { if (it.isLetterOrDigit()) it else '-' }
                .joinToString("")
                .trim('-')
                .replace(Regex("-+"), "-")
                .take(28)
                .ifBlank { "series" }
            val base = "$slug-${checksum(title.lowercase())}"
            var candidate = base
            var n = 2
            while (existing.any { it.id == candidate }) {
                candidate = "$base-$n"
                n++
            }
            return candidate
        }

        /** FNV-1a — small, stable across processes, not security relevant. */
        private fun checksum(s: String): String {
            var hash = 0x811C9DC5.toInt()
            for (c in s) {
                hash = hash xor c.code
                hash *= 0x01000193
            }
            return (hash.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0').take(6)
        }
    }
}
