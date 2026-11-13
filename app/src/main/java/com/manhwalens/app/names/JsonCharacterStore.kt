package com.manhwalens.app.names

import com.manhwalens.app.translate.NameScript
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
 *  - **the live file is never opened for writing.** Each save writes a complete copy to
 *    `<file>.tmp`, fsyncs it, then commits it with one atomic rename (startWrite →
 *    finishWrite → failWrite, the contract of `android.util.AtomicFile`). If anything fails
 *    before the rename, the previously stored file stays exactly as it was;
 *  - a `<file>.tmp` left behind by a crash is adopted only when it is complete (parses as JSON)
 *    *and* the live file is missing or unreadable — a stray copy can never shadow valid data;
 *  - recovery is **read-only with respect to the live file**: if the atomic promotion is refused,
 *    nothing is written anywhere — the records are read from the complete copy in place, the copy
 *    stays on disk for the next attempt, the live file keeps its exact bytes, and the failure is
 *    reported through [onWarning] instead of being thrown;
 *  - a corrupt file is set aside as `<file>.corrupt` and the store starts empty instead of
 *    crashing the pipeline — the unreadable original is kept for support;
 *  - a locked record can never be replaced by an automatic (unlocked) write.
 *
 * Why not `android.util.AtomicFile` itself: it lives in `android.jar`, whose methods are stubs
 * in JVM unit tests (this project sets `unitTests.isReturnDefaultValues = true` and Robolectric
 * is unavailable offline), so the persistence path — the one thing that must never corrupt the
 * reader's data — would become untestable. The same contract is implemented below with
 * `FileOutputStream` + `fd.sync()` + `Files.move(ATOMIC_MOVE)`, which the JVM tests exercise for
 * real.
 *
 * Thread-safe: the pipeline writes from the live and tap workers while the settings screen
 * writes from a background dispatcher.
 */
class JsonCharacterStore(
    private val file: File,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Commit seam: move [staging] over the live file. The default performs a real atomic move;
     * tests inject a refusing implementation to exercise the "promotion impossible" path
     * deterministically (a filesystem cannot be asked to fail on demand in a portable test).
     */
    private val atomicMove: (File, File) -> Boolean = ::atomicMoveOrRename,
    /**
     * Diagnostic sink for a recovery that had to be deferred. The JVM store stays Android-free,
     * so the Android layer ([com.manhwalens.app.data.CharacterMemory]) wires this to ErrorLog.
     */
    private val onWarning: (String) -> Unit = {}
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
        if (loadFrom(file)) return
        // The live file is missing, empty, corrupt or from a newer build. A complete staging file
        // left by an interrupted save is the only other copy: read it IN PLACE (never write to the
        // live path here) and try to promote it atomically. If promotion is impossible, both files
        // stay exactly as they are for a later attempt.
        val staging = stagingFile()
        if (!staging.exists() || staging.length() == 0L) return
        if (!isReadableJson(staging)) {
            // Half-written: it must never be mistaken for data.
            discard(staging)
            return
        }
        if (!loadFrom(staging)) return
        if (commitAtomically(staging)) return
        reportDeferredRecovery(staging)
    }

    /**
     * Read [target] into the in-memory index.
     *
     * @return true when [target] held a usable document (possibly an empty one), so the caller
     *         knows whether another copy is worth looking at. A corrupt or newer-schema live file
     *         is set aside exactly as before.
     */
    private fun loadFrom(target: File): Boolean {
        if (!target.exists()) return false
        val raw = runCatching { target.readText() }.getOrNull()
        if (raw.isNullOrBlank()) return false
        val root = try {
            JSONObject(raw)
        } catch (e: Exception) {
            // Corrupt JSON: never crash the translation pipeline over a name database.
            if (target == file) setAside("corrupt")
            bySeries.clear()
            return false
        }
        if (root.optInt("version", SCHEMA_VERSION) > SCHEMA_VERSION) {
            // Written by a newer build: keep the file, do not guess at its shape.
            if (target == file) setAside("newer")
            bySeries.clear()
            return false
        }
        val series = root.optJSONObject("series") ?: return true
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
        return true
    }

    /**
     * Atomic promotion was refused. Nothing is written: the staged copy is the newer, complete
     * document and stays on disk so a later load can promote it, while the live file (if any)
     * keeps its exact bytes. Reported through [onWarning] — never thrown.
     */
    private fun reportDeferredRecovery(staging: File) {
        runCatching {
            onWarning(
                "character memory: could not promote ${staging.name} atomically; " +
                    "kept for the next attempt (records loaded from it in memory)"
            )
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

        val staging = stagingFile()
        // startWrite: a complete, durable copy — the live file is not touched yet.
        if (!writeDurably(staging, root.toString())) {
            // failWrite: drop the incomplete copy; whatever is stored stays readable.
            discard(staging)
            return
        }
        // finishWrite: one atomic step turns the copy into the live file.
        if (commitAtomically(staging)) return
        // The filesystem refused an atomic move. Keep the complete copy (it is picked up on the
        // next load only if the live file is unreadable) instead of truncating valid data with an
        // in-place write — losing one save is recoverable, a half-written file is not.
    }

    private fun isReadableJson(target: File): Boolean {
        if (!target.exists() || target.length() == 0L) return false
        return runCatching { JSONObject(target.readText()) }.isSuccess
    }

    /** startWrite: write UTF-8 off to the side and fsync so the bytes outlive a power cut. */
    private fun writeDurably(target: File, text: String): Boolean = try {
        target.parentFile?.mkdirs()
        FileOutputStream(target).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        true
    } catch (e: Exception) {
        false
    }

    /**
     * finishWrite: commit [staging] over the live file in one atomic step — or not at all.
     * There is deliberately no copy-into-the-live-file fallback: a partial copy would destroy
     * the very data this store exists to protect.
     */
    private fun commitAtomically(staging: File): Boolean =
        runCatching { atomicMove(staging, file) }.getOrDefault(false)

    /** failWrite: remove the staged copy; never propagates, never touches the live file. */
    private fun discard(staging: File) {
        runCatching { staging.delete() }
    }

    private fun stagingFile(): File = File(file.parentFile, file.name + ".tmp")

    companion object {
        /** Bump only together with a reader for the older shape in [ensureLoaded]. */
        const val SCHEMA_VERSION = 1
    }
}

/**
 * Default commit: an atomic move when the filesystem supports one, otherwise a plain rename —
 * which is atomic for two names in the same directory on every Android filesystem we support.
 * Returns false instead of throwing; the caller keeps both files untouched.
 */
private fun atomicMoveOrRename(from: File, to: File): Boolean {
    val moved = runCatching {
        Files.move(
            from.toPath(),
            to.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    }.isSuccess
    if (moved) return true
    return runCatching { from.renameTo(to) }.getOrDefault(false)
}
