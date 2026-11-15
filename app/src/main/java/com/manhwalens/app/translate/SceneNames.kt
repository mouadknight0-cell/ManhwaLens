package com.manhwalens.app.translate

/**
 * Glue between [NameMemory]'s per-page plan and the engines (v0.11.0).
 *
 * ML Kit cannot follow instructions — the only way to stabilise a name on-device is to put
 * the Arabic spelling INTO the source text before the engine runs. This object does exactly
 * that for one page and remembers nothing across pages (a name is "unknown" again on the
 * next page unless the reader put it in the glossary or the model produced it again).
 *
 * The table only ever contains names the planner is confident about (Asian romanization,
 * a repeated mention, a title in front, or a model spelling seen twice), so an ordinary
 * English word is never swapped for Arabic letters.
 *
 * Pure Kotlin (JVM-tested).
 */
object SceneNames {

    /** Empty plan (used when the page has no detectable names). */
    val EMPTY: Map<String, String> = emptyMap()

    /**
     * Names to pin for this page: every planned name that is NOT already handled by
     * [Glossary] (those are replaced by [Glossary.prepare] anyway) and that is longer than
     * three characters or multi-word, so short ambiguous tokens are left to the engine.
     */
    fun pin(texts: List<String>, known: Map<String, String> = Glossary.effective()): Map<String, String> {
        if (texts.isEmpty()) return EMPTY
        val plan = runCatching { NameMemory.plan(texts, known) }.getOrDefault(NameMemory.Plan(emptyList(), emptyMap()))
        if (plan.isEmpty) return EMPTY
        val knownKeys = known.keys.map { NameMemory.looseKey(it) }.toSet()
        val out = LinkedHashMap<String, String>()
        for (p in plan.pinned) {
            if (p.arabic.isBlank()) continue
            if (NameMemory.looseKey(p.text) in knownKeys) continue  // Glossary already does it
            if (p.origin == NameMemory.Pinned.Origin.USER) continue // user entry, same path
            val hasSpace = p.text.contains(' ')
            if (!hasSpace && p.text.length < 5 && p.origin == NameMemory.Pinned.Origin.TRANSLITERATION) continue
            out[p.text] = p.arabic
        }
        // Longest first: "Wei Wuxian" must win over "Wei".
        return out.entries.sortedByDescending { it.key.length }.associateTo(LinkedHashMap()) { it.key to it.value }
    }

    /**
     * Replace every pinned name (whole words, case-insensitive) in [text]. Runs AFTER
     * [Glossary.prepare], so a name already converted by the glossary is simply not present
     * any more.
     */
    fun apply(text: String, table: Map<String, String>): String {
        if (table.isEmpty() || text.isEmpty()) return text
        var out = text
        for ((name, arabic) in table) {
            val pattern = Regex("(?i)(?<![\\p{L}])" + Regex.escape(name) + "(?![\\p{L}])")
            if (!pattern.containsMatchIn(out)) continue
            out = pattern.replace(out, arabic)
        }
        return out
    }

    /** One-line description for the pipeline trace. */
    fun describe(table: Map<String, String>): String =
        if (table.isEmpty()) "no new names"
        else table.entries.take(6).joinToString(", ") { "${it.key}→${it.value}" } +
            (if (table.size > 6) " +${table.size - 6}" else "")
}
