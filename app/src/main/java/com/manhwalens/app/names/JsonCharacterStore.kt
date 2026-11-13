package com.manhwalens.app.names

import com.manhwalens.app.translate.NameScript
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * JSON-file character store (P1) — the smallest reliable option for this data set.
 *
 * WHY NOT ROOM: a series holds tens of records (a few kB). Room would add an annotation
 * processor, schema export, migration plumbing and a new dependency to query a list that fits
 * in one page of memory; the app already persists JSON atomically for the translation cache,
 * so this reuses a proven pattern and the same failure handling. Everything Room would buy us
 * for this data — per-series queries, alias lookup, locked rows, restart persistence, schema
 * versioning — is provided here by the tolerant reader in [CharacterRecord.fromJson] plus
 * [SCHEMA_VERSION]. See P1_CHARACTER_MEMORY.md for the full comparison.
 *
 * Safety properties:
 *  - one in-memory index per file, loaded once (no full-table scan per OCR block);
 *  - writes go to `<file>.tmp` and are renamed over the original (never a half file);
 *  - a corrupt file is set aside as `<file>.corrupt` and the store starts empty instead of
 *    crashing the pipeline — the unreadable original is kept for support;
 *  - a locked record can never be replaced by an automatic (unlocked) write.
 *
 * Thread-safe: the pipeline writes from the live and tap workers while the settings screen
 * writes from a background dispatcher.
 */
class JsonCharacterStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis
) : CharacterStore {

    /** seriesId → records, in creation order. Guarded by [lock]. */
    private val bySeries = LinkedHashMap<String, MutableList<CharacterRecord>>()
    private val lock = Any()
    private var loaded = false
    private var nextSuffix = 1

    // ------------------------------------------------------------------ reads

    override fun records(seriesId: String): List<CharacterRecord> {
        synchronized(lock) {
            ensureLoaded()
            return bySeries[seriesId]?.toList() ?: emptyList()
        }
    }

    override fun find(seriesId: String, normalizedName: String): CharacterRecord? {
        synchronized(lock) {
            ensureLoaded()
            val key = NameForm.normalize(normalizedName)
            if (key.isEmpty()) return null
            val list = bySeries[seriesId] ?: return null
            return list.firstOrNull { it.normalizedName == key }
                ?: list.firstOrNull { r -> r.aliases.any { NameForm.normalize(it) == key } }
        }
    }

    override fun findById(id: String): CharacterRecord? {
        synchronized(lock) {
            ensureLoaded()
            for (list in bySeries.values) {
                list.firstOrNull { it.id == id }?.let { return it }
            }
            return null
        }
    }

    override fun seriesIds(): List<String> {
        synchronized(lock) {
            ensureLoaded()
            return bySeries.filterValues { it.isNotEmpty() }.keys.toList()
        }
    }

    // ------------------------------------------------------------------ writes

    override fun upsert(record: CharacterRecord): CharacterRecord {
        synchronized(lock) {
            ensureLoaded()
            val list = bySeries.getOrPut(record.seriesId) { ArrayList() }
            val index = list.indexOfFirst {
                it.id == record.id || it.normalizedName == record.normalizedName
            }
            if (index < 0) {
                val inserted = record.copy(id = record.id.ifBlank { newId(record.seriesId) })
                list += inserted
                saveLocked()
                return inserted
            }
            val existing = list[index]
            // A locked record is the reader's decision: an automatic write may not touch it.
            if (existing.locked && !record.locked) return existing
            val merged = existing.copy(
                originalName = record.originalName.ifBlank { existing.originalName },
                arabicName = record.arabicName.ifBlank { existing.arabicName },
                script = if (record.script != NameScript.UNKNOWN) record.script else existing.script,
                aliases = (existing.aliases + record.aliases).distinct(),
                confidence = maxOf(existing.confidence, record.confidence),
                source = if (record.source.priority <= existing.source.priority) record.source else existing.source,
                locked = existing.locked || record.locked,
                updatedAt = clock(),
                previousArabic = (existing.previousArabic + record.previousArabic)
                    .distinct().takeLast(CharacterRecord.MAX_PREVIOUS),
                occurrences = maxOf(existing.occurrences, record.occurrences)
            )
            list[index] = merged
            saveLocked()
            return merged
        }
    }

    override fun lock(id: String, arabic: String, now: Long): CharacterRecord? =
        mutate(id) { r ->
            val clean = arabic.trim()
            if (clean.isBlank()) r
            else r.withArabic(clean, NameSource.USER, 1f, now).copy(locked = true, updatedAt = now)
        }

    override fun unlock(id: String, now: Long): CharacterRecord? =
        mutate(id) { r -> r.copy(locked = false, updatedAt = now) }

    override fun updateArabic(id: String, arabic: String, source: NameSource, now: Long): CharacterRecord? =
        mutate(id) { r ->
            val clean = arabic.trim()
            when {
                clean.isBlank() -> r
                // A locked spelling changes through the explicit lock flow only.
                r.locked -> r
                else -> r.withArabic(clean, source, maxOf(r.confidence, 0.9f), now)
            }
        }

    override fun addAlias(id: String, alias: String, now: Long): CharacterRecord? =
        mutate(id) { r ->
            val clean = alias.trim()
            val key = NameForm.normalize(clean)
            when {
                key.isEmpty() || key == r.normalizedName -> r
                r.aliases.any { NameForm.normalize(it) == key } -> r
                else -> r.copy(aliases = r.aliases + clean, updatedAt = now)
            }
        }

    override fun delete(id: String): Boolean {
        synchronized(lock) {
            ensureLoaded()
            var removed = false
            for (list in bySeries.values) {
                if (list.removeAll { it.id == id }) removed = true
            }
            if (removed) saveLocked()
            return removed
        }
    }

    override fun deleteSeries(seriesId: String): Int {
        synchronized(lock) {
            ensureLoaded()
            val removed = bySeries.remove(seriesId)?.size ?: 0
            if (removed > 0) saveLocked()
            return removed
        }
    }

    // ------------------------------------------------------------------ internals

    private fun mutate(id: String, body: (CharacterRecord) -> CharacterRecord): CharacterRecord? {
        synchronized(lock) {
            ensureLoaded()
            for (list in bySeries.values) {
                val index = list.indexOfFirst { it.id == id }
                if (index < 0) continue
                val current = list[index]
                val updated = body(current)
                if (updated !== current) {
                    list[index] = updated
                    saveLocked()
                }
                return updated
            }
            return null
        }
    }

    private fun newId(seriesId: String): String = "$seriesId-${clock()}-${nextSuffix++}"

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        val raw = runCatching { file.readText() }.getOrNull()
        if (raw.isNullOrBlank()) return
        try {
            val root = JSONObject(raw)
            val version = root.optInt("version", SCHEMA_VERSION)
            if (version > SCHEMA_VERSION) {
                // Written by a newer build: keep the file, do not guess at its shape.
                setAside("newer")
                return
            }
            val series = root.optJSONObject("series") ?: return
            for (seriesId in series.keys()) {
                val arr = series.optJSONArray(seriesId) ?: continue
                val list = ArrayList<CharacterRecord>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val record = CharacterRecord.fromJson(o) ?: continue
                    if (record.seriesId == seriesId) list += record
                }
                if (list.isNotEmpty()) bySeries[seriesId] = list
            }
        } catch (e: Exception) {
            // Corrupt JSON: never crash the translation pipeline over a name database.
            setAside("corrupt")
            bySeries.clear()
        }
    }

    /** Keep the unreadable file for support instead of silently deleting the reader's data. */
    private fun setAside(reason: String) {
        runCatching { file.renameTo(File(file.parentFile, file.name + ".$reason")) }
    }

    private fun saveLocked() {
        val root = JSONObject()
        root.put("version", SCHEMA_VERSION)
        val series = JSONObject()
        for ((seriesId, list) in bySeries) {
            val arr = JSONArray()
            for (r in list) arr.put(r.toJson())
            series.put(seriesId, arr)
        }
        root.put("series", series)
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            file.parentFile?.mkdirs()
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        } catch (e: Exception) {
            runCatching { tmp.delete() }
        }
    }

    companion object {
        /** Bump only together with a reader for the older shape in [ensureLoaded]. */
        const val SCHEMA_VERSION = 1
    }
}
