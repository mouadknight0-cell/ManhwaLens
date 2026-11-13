package com.manhwalens.app.translate

import org.json.JSONObject
import java.io.File

/**
 * Disk-backed LRU layer underneath the in-memory [TranslationCache].
 *
 * Stores the SAME keys (`engine|style|normalized text`) so a string translated
 * in an earlier session never hits the network/engine again. Persisted as one
 * JSON object; writes go to a temp file + atomic rename so a half-written file
 * can never corrupt the cache. A missing or corrupt file starts empty.
 *
 * Bounded to [maxEntries] (LRU eviction, oldest writes dropped first — access
 * order). Pure JVM + java.io.File, so it is unit-testable.
 *
 * Thread-safety: all public methods are @Synchronized.
 */
class PersistentTranslationCache(
    private val file: File,
    private val maxEntries: Int = 2000
) {

    private val map = LinkedHashMap<String, String>(16, 0.75f, /* accessOrder = */ true)
    private var loaded = false

    /** Load (once) from disk and drop anything over capacity. */
    private fun ensureLoaded(): LinkedHashMap<String, String> {
        if (loaded) return map
        loaded = true
        val raw = runCatching { if (file.exists()) file.readText(Charsets.UTF_8) else null }.getOrElse {
            // Unreadable file — delete it; a missing cache is recoverable, a crashing cache is not.
            runCatching { file.delete() }
            return map
        }
        if (raw.isNullOrBlank()) return map
        val obj = runCatching { JSONObject(raw) }.getOrElse {
            // Corrupt/partial file (killed mid-write, bad restore) — self-heal: drop it.
            runCatching { file.delete() }
            return map
        }
        val entries = obj.keys().asSequence().map { it to (obj.optString(it, "")) }.toList()
        // Re-apply LRU order from oldest to newest as stored (JSON keys unordered —
        // insertion order == what we wrote, so keep first-2000 by iteration).
        for ((k, v) in entries) {
            if (v.isNotEmpty()) map[k] = v
            if (map.size > maxEntries) map.remove(map.keys.iterator().next())
        }
        return map
    }

    @Synchronized
    fun get(key: String): String? {
        return ensureLoaded()[key]
    }

    @Synchronized
    fun put(key: String, value: String) {
        val m = ensureLoaded()
        m[key] = value
        while (m.size > maxEntries) {
            val it = m.entries.iterator()
            it.next()
            it.remove()
        }
        save()
    }

    @Synchronized
    fun putAll(entries: Map<String, String>) {
        val m = ensureLoaded()
        for ((k, v) in entries) {
            m[k] = v
        }
        while (m.size > maxEntries) {
            val it = m.entries.iterator()
            it.next()
            it.remove()
        }
        save()
    }

    @Synchronized
    fun remove(key: String) {
        if (ensureLoaded().remove(key) != null) save()
    }

    @Synchronized
    fun size(): Int = ensureLoaded().size

    @Synchronized
    fun clear() {
        map.clear()
        loaded = true
        runCatching { if (file.exists()) file.delete() }
    }

    /** Atomic write: temp file then rename. Failures are logged, never thrown. */
    private fun save() {
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            file.parentFile?.mkdirs()
            tmp.writeText(obj.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                // Some filesystems refuse rename-over-existing: fall back to direct write.
                file.writeText(obj.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            // Never let a cache-write failure break a translation.
            runCatching { tmp.delete() }
        }
    }
}
