package com.manhwalens.app.names

/**
 * Persistence seam for character records (P1).
 *
 * Deliberately tiny and synchronous: one page needs **one** query ([records]) and at most one
 * write, and every implementation is cheap because a series holds tens of records, not
 * thousands. All methods must be called off the main thread — the pipeline already runs name
 * resolution on the capture / live worker, and the settings screen uses a background
 * dispatcher.
 *
 * Implementations must never lose a locked record to an automatic write.
 */
interface CharacterStore {

    /** Every record of [seriesId], original spelling order (creation order). */
    fun records(seriesId: String): List<CharacterRecord>

    /** Lookup by lookup key ([NameForm.normalize]) — includes alias hits. */
    fun find(seriesId: String, normalizedName: String): CharacterRecord?

    fun findById(id: String): CharacterRecord?

    /**
     * Insert [record] or update it **without ever overwriting a locked record** with an
     * unlocked one. Returns the record as stored.
     */
    fun upsert(record: CharacterRecord): CharacterRecord

    /** Confirm [arabic] for [id] and lock it. The previous spelling is kept for cache repair. */
    fun lock(id: String, arabic: String, now: Long): CharacterRecord?

    /** Drop the lock but keep the spelling (the reader can then correct it again later). */
    fun unlock(id: String, now: Long): CharacterRecord?

    /** Change the Arabic spelling of an unlocked record. Locked records are left untouched. */
    fun updateArabic(id: String, arabic: String, source: NameSource, now: Long): CharacterRecord?

    /** Add an alternative Latin spelling. Never replaces the original name. */
    fun addAlias(id: String, alias: String, now: Long): CharacterRecord?

    fun delete(id: String): Boolean

    /** Remove every record of a deleted series (no orphan mappings). */
    fun deleteSeries(seriesId: String): Int

    fun seriesIds(): List<String>
}
