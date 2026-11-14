package com.manhwalens.app.names

import com.manhwalens.app.translate.NameScript
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which layer decided a character's Arabic spelling. Ordered by trust:
 * a locked user correction must never be displaced by anything below it.
 *
 * P1 keeps the four origins [com.manhwalens.app.translate.NameMemory] already uses (USER,
 * BUILT_IN, PAGE_AI, TRANSLITERATION) and adds HEURISTIC for a detection that was stored
 * from romanization evidence alone, so the reader can see *why* a name looks the way it does.
 */
enum class NameSource {
    /** The reader typed this spelling themselves (and usually locked it). */
    USER,

    /** The curated built-in table shipped with the app. */
    BUILT_IN,

    /** A spelling an instructable model produced for this page (Gemini). */
    PAGE_AI,

    /** Stored from romanization evidence with confidence above the trust floor. */
    HEURISTIC,

    /** A deterministic machine transliteration — never treated as confirmed. */
    TRANSLITERATION;

    /** Lower wins. Used by [CharacterResolver]; kept here so the order lives in one place. */
    val priority: Int
        get() = when (this) {
            USER -> 1
            BUILT_IN -> 2
            PAGE_AI -> 3
            HEURISTIC -> 4
            TRANSLITERATION -> 5
        }
}

/**
 * One remembered character of ONE series (v0.11.0 P1).
 *
 * The whole point of P1 is that this record survives the page: the same person must be spelled
 * the same way in the next bubble, the next page, the next chapter and after a restart. So the
 * record owns two *separate* facts that must never overwrite each other:
 *
 *  - [originalName] — the Latin spelling exactly as the reader met it ("Han Ji-woo"). Any
 *    alias is ADDITIONAL ([aliases]); adding one never rewrites the original.
 *  - [arabicName] — the Arabic spelling. [locked] means the reader confirmed it, and a locked
 *    record is never rewritten by automatic prediction.
 *
 * [previousArabic] holds earlier Arabic spellings that were replaced by a correction. Cache
 * hits are rewritten from those (see [com.manhwalens.app.translate.CachingTranslator]), which
 * is how a correction takes effect immediately instead of waiting for the cache to expire.
 *
 * [confidence] is the confidence of the *evidence*, not of the reader: a machine
 * transliteration stays below [TRUST_MIN] and is therefore never "confirmed".
 */
data class CharacterRecord(
    val id: String,
    val seriesId: String,
    val originalName: String,
    /** Lookup key — see [NameForm.normalize]. Not for display. */
    val normalizedName: String,
    val arabicName: String,
    val script: NameScript,
    val aliases: List<String> = emptyList(),
    val confidence: Float = 0f,
    val source: NameSource = NameSource.HEURISTIC,
    val locked: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    /** Arabic spellings this record used before it was corrected (rewritten on cache hits). */
    val previousArabic: List<String> = emptyList(),
    /** How often the name was seen when it was stored (informational). */
    val occurrences: Int = 0
) {

    /** True when this spelling may be applied without asking the reader again. */
    val isTrusted: Boolean
        get() = locked || source == NameSource.USER || source == NameSource.BUILT_IN || confidence >= TRUST_MIN

    /** Every Latin form this character is known by, original first. */
    val allSpellings: List<String>
        get() = listOf(originalName) + aliases

    fun withArabic(arabic: String, source: NameSource, confidence: Float, now: Long): CharacterRecord =
        copy(
            arabicName = arabic,
            source = source,
            confidence = confidence,
            updatedAt = now,
            previousArabic = if (arabic.isBlank() || arabic == arabicName) previousArabic
            else (previousArabic + arabicName).filter { it.isNotBlank() }.distinct().takeLast(MAX_PREVIOUS)
        )

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("seriesId", seriesId)
        put("originalName", originalName)
        put("normalizedName", normalizedName)
        put("arabicName", arabicName)
        put("script", script.name)
        put("aliases", JSONArray(aliases))
        put("confidence", confidence.toDouble())
        put("source", source.name)
        put("locked", locked)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("previousArabic", JSONArray(previousArabic))
        put("occurrences", occurrences)
    }

    companion object {

        /** Below this the Arabic spelling is a guess: shown, never locked, never trusted alone. */
        const val TRUST_MIN = 0.6f

        /** A correction keeps at most this many superseded spellings for cache rewriting. */
        const val MAX_PREVIOUS = 4

        /** Tolerant reader: a corrupted or partial object returns null instead of throwing. */
        fun fromJson(o: JSONObject): CharacterRecord? = try {
            val id = o.optString("id", "")
            val seriesId = o.optString("seriesId", "")
            val original = o.optString("originalName", "")
            if (id.isBlank() || seriesId.isBlank() || original.isBlank()) {
                null
            } else {
                CharacterRecord(
                    id = id,
                    seriesId = seriesId,
                    originalName = original,
                    normalizedName = o.optString("normalizedName", "").ifBlank { NameForm.normalize(original) },
                    arabicName = o.optString("arabicName", ""),
                    script = runCatching { NameScript.valueOf(o.optString("script", "UNKNOWN")) }
                        .getOrDefault(NameScript.UNKNOWN),
                    aliases = o.optJSONArray("aliases")?.toStringList().orEmpty(),
                    confidence = o.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f),
                    source = runCatching { NameSource.valueOf(o.optString("source", "HEURISTIC")) }
                        .getOrDefault(NameSource.HEURISTIC),
                    locked = o.optBoolean("locked", false),
                    createdAt = o.optLong("createdAt", 0L),
                    updatedAt = o.optLong("updatedAt", 0L),
                    previousArabic = o.optJSONArray("previousArabic")?.toStringList().orEmpty(),
                    occurrences = o.optInt("occurrences", 0)
                )
            }
        } catch (e: Exception) {
            null
        }

        private fun JSONArray.toStringList(): List<String> {
            val out = ArrayList<String>(length())
            for (i in 0 until length()) {
                val v = optString(i, "")
                if (v.isNotBlank()) out += v
            }
            return out
        }
    }
}
