package com.manhwalens.app.names

import com.manhwalens.app.translate.Glossary
import com.manhwalens.app.translate.NameMemory
import com.manhwalens.app.translate.NameScript

/**
 * One resolved name of a page: what the reader wrote, what we will render, and why.
 * [arabic] is null when the honest answer is "keep the Latin spelling" (requirement 7).
 */
data class ResolvedName(
    val original: String,
    val arabic: String?,
    val script: NameScript,
    val confidence: Float,
    val source: NameSource,
    val locked: Boolean,
    val recordId: String?,
    /** True when [CharacterWriter] may store this as a new series record. */
    val persistable: Boolean,
    /** Short, log-safe explanation ("locked", "built-in", "series memory", …). */
    val reason: String
)

/**
 * The authoritative page-level name table (P1).
 *
 * One instance per page: the same object feeds the inline substitution for ML Kit
 * ([table]), the name sheet for Gemini ([sheet]) and the persisted series memory. Keeping it
 * single-sourced is what makes "ML Kit and Gemini use consistent name mappings" true by
 * construction instead of by coincidence.
 */
data class PageNameTable(
    val names: List<ResolvedName>,
    val seriesId: String?
) {

    /** Latin spelling → Arabic, longest first so "Wei Wuxian" wins over "Wei". */
    val table: Map<String, String> by lazy {
        val out = LinkedHashMap<String, String>(names.size)
        for (n in names.sortedByDescending { it.original.length }) {
            val arabic = n.arabic
            if (!arabic.isNullOrBlank()) out[n.original] = arabic
        }
        out
    }

    /** "Name = عربي; …" — the sheet Gemini receives (P0-A path, now series-aware). */
    val sheet: String by lazy {
        names.filter { !it.arabic.isNullOrBlank() }
            .take(SHEET_LIMIT)
            .joinToString("; ") { "${it.original} = ${it.arabic}" }
    }

    val isEmpty: Boolean get() = names.isEmpty()

    val pinnedCount: Int get() = table.size

    fun arabicFor(original: String): String? =
        names.firstOrNull { it.original.equals(original, ignoreCase = true) }?.arabic

    fun describe(): String =
        if (names.isEmpty()) "no names"
        else names.take(6).joinToString(", ") { "${it.original}→${it.arabic ?: "latin"}(${it.reason})" } +
            (if (names.size > 6) " +${names.size - 6}" else "")

    companion object {
        const val SHEET_LIMIT = 20
        val EMPTY = PageNameTable(emptyList(), null)
    }
}

/**
 * Priority resolution for character names (P1).
 *
 * The order below is the product requirement, implemented once so no caller can invent its
 * own variant:
 *
 *  1. **locked** user-confirmed record — never overwritten by anything automatic;
 *  2. **user-defined** spelling — the reader's glossary entry, or their own series record;
 *  3. **built-in** curated mapping;
 *  4. **previously confirmed series memory** (trusted record of this series);
 *  5. **high-confidence automatic detection** (a spelling the model produced for this page);
 *  6. **transliteration** prediction (deterministic, low trust);
 *  7. **the original Latin spelling** when nothing above is available.
 *
 * Two guarantees the tests pin down: a locked record always wins, and an uncertain name keeps
 * its Latin spelling instead of receiving an invented Arabic one.
 *
 * Pure Kotlin apart from the [CharacterStore] seam (JVM-tested with an in-memory store).
 */
class CharacterResolver(
    private val store: CharacterStore? = null
) {

    fun resolve(
        seriesId: String?,
        texts: List<String>,
        known: Map<String, String> = Glossary.effective()
    ): PageNameTable {
        if (texts.isEmpty()) return PageNameTable.EMPTY
        val plan = runCatching { NameMemory.plan(texts, known) }
            .getOrDefault(NameMemory.Plan(emptyList(), emptyMap()))
        val records = if (seriesId.isNullOrBlank() || store == null) emptyList()
        else runCatching { store.records(seriesId) }.getOrDefault(emptyList())

        val byKey = HashMap<String, CharacterRecord>(records.size)
        val byAlias = HashMap<String, CharacterRecord>(records.size)
        for (r in records) {
            byKey[r.normalizedName] = r
            for (a in r.aliases) byAlias.putIfAbsent(NameForm.normalize(a), r)
        }

        val out = ArrayList<ResolvedName>(plan.pinned.size + records.size)
        val used = HashSet<String>()

        // Detected candidates, in the planner's own (longest-first) order.
        for (p in plan.pinned) {
            val key = NameForm.normalize(p.text)
            if (key.isEmpty() || !used.add(key)) continue
            out += decide(p, byKey[key] ?: byAlias[key])
        }

        // Remembered characters the page scanner did not surface: a locked name must be
        // applied even when the token is an English word the scanner deliberately drops.
        if (records.isNotEmpty()) {
            val page = texts.joinToString("\n")
            for (r in records) {
                if (!used.add(r.normalizedName)) continue
                if (r.arabicName.isBlank()) continue
                if (!NameForm.appearsIn(page, r.originalName, r.aliases)) continue
                out += ResolvedName(
                    original = r.originalName,
                    arabic = r.arabicName,
                    script = r.script,
                    confidence = if (r.locked) 1f else r.confidence,
                    source = if (r.locked) NameSource.USER else r.source,
                    locked = r.locked,
                    recordId = r.id,
                    persistable = false,
                    reason = if (r.locked) "locked" else "series memory"
                )
            }
        }

        return PageNameTable(out.sortedByDescending { it.original.length }, seriesId)
    }

    /** The priority ladder for one detected candidate. See the class KDoc. */
    private fun decide(p: NameMemory.Pinned, record: CharacterRecord?): ResolvedName {
        val script = NameScript.classify(p.text)
        val ambiguous = NameForm.isAmbiguousEnglishWord(p.text)

        // 1 — a locked record is the reader's decision, full stop.
        if (record != null && record.locked && record.arabicName.isNotBlank()) {
            return ResolvedName(
                p.text, record.arabicName, script.script, 1f, NameSource.USER, locked = true,
                recordId = record.id, persistable = false, reason = "locked"
            )
        }

        // 2 — an explicit user spelling: a series record the reader created, then their
        //     glossary entry. Both are the reader's own words, so they outrank everything
        //     automatic.
        if (record != null && record.source == NameSource.USER && !record.locked && record.arabicName.isNotBlank()) {
            return ResolvedName(
                p.text, record.arabicName, script.script, 1f, NameSource.USER, locked = false,
                recordId = record.id, persistable = false, reason = "series (user)"
            )
        }
        if (p.origin == NameMemory.Pinned.Origin.USER) {
            return ResolvedName(
                p.text, p.arabic, script.script, 1f, NameSource.USER, locked = false,
                recordId = record?.id, persistable = record == null, reason = "glossary (user)"
            )
        }

        // 3 — the curated built-in table.
        if (p.origin == NameMemory.Pinned.Origin.BUILT_IN) {
            return ResolvedName(
                p.text, p.arabic, script.script, 0.9f, NameSource.BUILT_IN, locked = false,
                recordId = record?.id, persistable = !ambiguous && record == null, reason = "built-in"
            )
        }

        // 4 — previously confirmed series memory (never a bare guess: isTrusted gates it).
        if (record != null && record.isTrusted && record.arabicName.isNotBlank()) {
            return ResolvedName(
                p.text, record.arabicName, record.script, record.confidence, record.source,
                locked = false, recordId = record.id, persistable = false, reason = "series memory"
            )
        }

        // 5 — a spelling the model produced from the page itself.
        if (p.origin == NameMemory.Pinned.Origin.PAGE_AI) {
            return ResolvedName(
                p.text, p.arabic, script.script, 0.8f, NameSource.PAGE_AI, locked = false,
                recordId = record?.id, persistable = !ambiguous && record == null, reason = "page model"
            )
        }

        // 6 — deterministic transliteration: usable on the page, but not "confirmed".
        if (p.origin == NameMemory.Pinned.Origin.TRANSLITERATION && !ambiguous) {
            val confidence = script.confidence.coerceIn(0f, 0.7f)
            return ResolvedName(
                p.text, p.arabic, script.script, confidence, NameSource.TRANSLITERATION,
                locked = false, recordId = record?.id,
                // Only a confidently identified, repeated name is worth remembering.
                persistable = record == null && script.isConfident && p.count >= 2,
                reason = "transliteration"
            )
        }

        // 7 — nothing trustworthy: keep the Latin spelling (requirement 7).
        return ResolvedName(
            p.text, null, script.script, script.confidence, NameSource.HEURISTIC,
            locked = false, recordId = record?.id, persistable = false,
            reason = if (ambiguous) "ambiguous english word" else "uncertain"
        )
    }
}

/**
 * Writes eligible detections into the series memory (P1).
 *
 * Only what [CharacterResolver] marked persistable is stored, and an existing record is never
 * rewritten (no silent merges, no replaced originals). With no series selected nothing is
 * written at all — an unassigned session must not pollute a story the reader never chose.
 */
class CharacterWriter(private val store: CharacterStore?) {

    /** @return how many NEW records were created (0 when nothing was eligible). */
    fun commit(table: PageNameTable, now: Long = System.currentTimeMillis()): Int {
        val seriesId = table.seriesId
        if (seriesId.isNullOrBlank() || store == null) return 0
        var created = 0
        for (n in table.names) {
            val arabic = n.arabic
            if (!n.persistable || arabic.isNullOrBlank()) continue
            val key = NameForm.normalize(n.original)
            if (key.isEmpty()) continue
            val existing = runCatching { store.find(seriesId, key) }.getOrNull()
            if (existing != null) continue // never overwrite, never merge
            val record = CharacterRecord(
                id = "",
                seriesId = seriesId,
                originalName = n.original,
                normalizedName = key,
                arabicName = arabic,
                script = n.script,
                aliases = emptyList(),
                confidence = n.confidence,
                source = when (n.source) {
                    NameSource.USER -> NameSource.USER
                    NameSource.BUILT_IN -> NameSource.BUILT_IN
                    NameSource.PAGE_AI -> NameSource.PAGE_AI
                    NameSource.TRANSLITERATION, NameSource.HEURISTIC -> NameSource.HEURISTIC
                },
                locked = false,
                createdAt = now,
                updatedAt = now,
                occurrences = 0
            )
            val stored = runCatching { store.upsert(record) }.getOrNull() ?: continue
            if (stored.originalName == n.original) created++
        }
        return created
    }
}
