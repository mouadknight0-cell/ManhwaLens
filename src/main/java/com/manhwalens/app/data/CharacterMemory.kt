package com.manhwalens.app.data

import android.content.Context
import com.manhwalens.app.names.CharacterRecord
import com.manhwalens.app.names.CharacterResolver
import com.manhwalens.app.names.CharacterStore
import com.manhwalens.app.names.CharacterWriter
import com.manhwalens.app.names.JsonCharacterStore
import com.manhwalens.app.names.NameForm
import com.manhwalens.app.names.PageNameTable
import com.manhwalens.app.names.Series
import com.manhwalens.app.names.SeriesCatalog
import com.manhwalens.app.util.PipelineTracer
import java.io.File

/**
 * Process-wide entry point to the P1 character memory.
 *
 * It owns three things and nothing else:
 *  - the single [JsonCharacterStore] instance over `filesDir/character_memory.json`;
 *  - the [SeriesCatalog] over [SeriesPrefs] (which series the reader is curating);
 *  - a per-page [NameSession], which is the only object the translation pipeline touches.
 *
 * Threading: every method here is meant for a **background** thread. The pipeline already
 * calls the factory from the capture / live worker, and the settings screen wraps its reads
 * and writes in a background dispatcher. Nothing here performs I/O on the main thread by
 * itself — no database, no network, fully offline.
 */
object CharacterMemory {

    private const val FILE_NAME = "character_memory.json"

    @Volatile
    private var storeCache: JsonCharacterStore? = null

    /** The shared store. Safe to call from any worker thread. */
    fun store(context: Context): JsonCharacterStore {
        storeCache?.let { return it }
        return synchronized(this) {
            storeCache ?: JsonCharacterStore(
                File(context.applicationContext.filesDir, FILE_NAME),
                // A deferred recovery (the filesystem refused to promote a complete copy) is a
                // data-integrity warning: surface it in the pipeline trace rather than in the
                // last-error file, which is reserved for crashes.
                onWarning = { message ->
                    runCatching { PipelineTracer.span("character memory", 0, message) }
                }
            ).also { storeCache = it }
        }
    }

    fun catalog(context: Context): SeriesCatalog = SeriesCatalog(SeriesPrefs(context))

    /** The series the reader selected, or null when the session is unassigned. */
    fun activeSeries(context: Context): Series? =
        runCatching { catalog(context).active() }.getOrNull()

    /**
     * A session for one translated page. Cheap: it holds the selection and loads this
     * series' records once, then answers every name question of that page from memory.
     */
    fun session(context: Context): NameSession {
        val series = activeSeries(context)
        return NameSession(
            seriesId = series?.id,
            seriesTitle = series?.title,
            store = if (series == null) null else store(context)
        )
    }
}

/**
 * One page's view of the series memory (P1).
 *
 * [tableFor] is the authoritative page table: it resolves the names, stores the eligible ones
 * and is memoised so ML Kit, Gemini and the cache decorator all see the same answer.
 * [rewrite] applies confirmed spellings to a piece of Arabic that came from the cache, which is
 * how a correction takes effect on already-translated chapters.
 *
 * When no series is selected the session is **unassigned**: names still resolve for the page
 * (nothing regresses), but nothing is read from or written to series memory, so a mapping can
 * never be attributed to a story the reader did not choose.
 */
class NameSession(
    val seriesId: String?,
    val seriesTitle: String?,
    private val store: CharacterStore?,
    private val now: () -> Long = System::currentTimeMillis
) {

    private val resolver = CharacterResolver(store)
    private val writer = CharacterWriter(store)

    /** Loaded once per session: one query per page, never one per block. */
    private val records: List<CharacterRecord> by lazy {
        val id = seriesId
        // Snapshot the nullable field: smart casts do not survive into a lambda.
        val source = store
        if (id.isNullOrBlank() || source == null) emptyList()
        else runCatching { source.records(id) }.getOrDefault(emptyList())
    }

    private var cachedTable: PageNameTable? = null

    /**
     * The exact text list the cached table was computed from. Content equality (not
     * `texts.hashCode()`) is the identity: two different pages can share a hash, and reusing a
     * table across them would apply one page's names to another page's bubbles.
     */
    private var cachedTexts: List<String> = emptyList()

    val isAssigned: Boolean get() = !seriesId.isNullOrBlank()

    /**
     * Resolve this page's names and persist the eligible new ones. Memoised per text set, so
     * the local engine and the remote relay share one computation per page.
     */
    fun tableFor(texts: List<String>): PageNameTable {
        cachedTable?.let { if (cachedTexts == texts) return it }
        val table = resolver.resolve(seriesId, texts)
        if (isAssigned) runCatching { writer.commit(table, now()) }
        // Surfaced in the pipeline trace so the reader can see WHICH series a page is filed
        // under (or that nothing is being saved) while translating, not only in Settings.
        runCatching {
            PipelineTracer.span("character memory", 0, describe() + " · " + table.describe())
        }
        cachedTable = table
        // Defensive copy: a caller that mutates its list later must not alias the cache key.
        cachedTexts = texts.toList()
        return table
    }

    /**
     * Re-apply confirmed spellings to a translation that may pre-date a correction.
     *
     * Only trusted records (locked / user / built-in / confidence ≥ 0.6) may rewrite text, and
     * only for blocks whose English actually contains that character. Two repairs happen:
     * a Latin remnant becomes the canonical Arabic, and a superseded Arabic spelling becomes
     * the current one — so a locked name wins over an already-cached translation.
     */
    fun rewrite(english: String, arabic: String): String {
        if (arabic.isBlank() || records.isEmpty()) return arabic
        var out = arabic
        for (r in records) {
            if (!r.isTrusted) continue
            if (r.arabicName.isBlank()) continue
            if (!NameForm.appearsIn(english, r.originalName, r.aliases)) continue
            out = NameForm.replaceLatin(out, r.arabicName, NameForm.spellings(r))
            for (previous in r.previousArabic) {
                out = NameForm.replaceArabic(out, previous, r.arabicName)
            }
        }
        return out
    }

    /** Short log-safe description for the pipeline trace. */
    fun describe(): String =
        if (!isAssigned) "unassigned (names are session-only)"
        else "series=${seriesTitle ?: seriesId} records=${records.size}"
}
