package com.manhwalens.app.translate

/**
 * P2-05 (decision C′) — the ONE name table a page is translated with.
 *
 * Before this type existed the Gemini engine received only the *text* of the page's name sheet
 * (`sheetProvider: (List<String>) -> String`) and then rebuilt its own table from
 * [NameMemory.plan] for the post-processing repair. Two computations meant two answers, so a
 * locked series spelling could be replaced by a page-local transliteration
 * (REPOSITORY_REPORT.md §5.14.2, chain D-A).
 *
 * The sheet and the table therefore travel together, both taken from the same resolved
 * `PageNameTable` ([com.manhwalens.app.names.PageNameTable]):
 *
 *  - [sheet] is exactly `PageNameTable.sheet` — same entries, same order, same 20-line cap —
 *    so the prompt text is byte-identical to what the series memory produced before;
 *  - [table] is exactly `PageNameTable.table` — the Latin → Arabic map the repair applies.
 *
 * Because they are two views of one resolution they cannot disagree: what the model was told is
 * what the repair enforces, which is the whole point of the decision.
 *
 * Deliberately a plain value with no `names`-package dependency: the factory builds it, so the
 * engine keeps depending on `translate` only and no package cycle is introduced.
 *
 * Pure Kotlin (JVM-tested).
 */
data class NameSheet(
    /** The exact text handed to the model (empty when the page has no names). */
    val sheet: String,
    /** The resolved Latin → Arabic table the post-processing repair must apply. */
    val table: Map<String, String>
) {

    /** Nothing to tell the model and nothing to repair. */
    val isEmpty: Boolean get() = sheet.isBlank() && table.isEmpty()

    companion object {
        val EMPTY = NameSheet("", emptyMap())
    }
}
