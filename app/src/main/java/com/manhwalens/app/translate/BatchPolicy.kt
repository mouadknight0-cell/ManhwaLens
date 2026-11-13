package com.manhwalens.app.translate

/** Tiny key/value seam so the policy stays pure (SharedPreferences behind it on device). */
interface BatchStore {
    fun getString(key: String, def: String): String
    fun putString(key: String, value: String)
    fun getLong(key: String, def: Long): Long
    fun putLong(key: String, value: Long)
}

/**
 * Decides, per device, whether ML Kit pages go out as ONE joined call (and with which
 * [SplitStrategy]) or as parallel per-block calls (v0.9.9).
 *
 * Rules:
 *  - default = PARALLEL. A joined strategy is used only after [recordChoice] stored one
 *    that a self-test / benchmark proved on THIS device ([deviceKey]).
 *  - a joined mismatch at runtime ([noteJoinedFailure]) bans THAT separator for [BLOCK_MS]
 *    (7 days, persisted) and forgets the choice; the self-test then re-runs over the
 *    separators that are still allowed (v0.9.13 — one bad separator used to switch joined
 *    mode off entirely for a week, although "•" works on the same device). A second mismatch
 *    in the same session switches joined off until the app restarts, so at most two pages
 *    per session ever pay for a failed joined attempt.
 *
 * One instance per process (see [BatchPolicyHolder]) — `sessionBlocked` is the session memory.
 */
class BatchPolicy(
    private val store: BatchStore,
    private val deviceKey: String,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    @Volatile
    private var sessionBlocked = false
    @Volatile
    private var sessionFailures = 0

    init {
        // Validation rules changed (v0.9.11: an untranslated SFX is no longer a mismatch).
        // Bans and choices made under older rules are void — re-prove from scratch.
        if (store.getLong(KEY_RULES, 1L) != RULES_VERSION) {
            store.putLong(KEY_BLOCK_UNTIL, 0L)
            store.putString(KEY_BLOCK_WHY, "")
            store.putString(KEY_CHOICE, "")
            // v0.9.13: a level stored from a single noisy benchmark run (x6 on the test phone).
            store.putLong(KEY_PARALLEL_LEVEL, DEFAULT_PARALLEL.toLong())
            SplitStrategy.values().forEach { store.putLong(KEY_BAN_PREFIX + it.id, 0L) }
            store.putLong(KEY_RULES, RULES_VERSION)
        }
    }

    /** Per-block calls in flight. Default 2; the benchmark stores the level it measured best. */
    fun parallelLevel(): Int = store.getLong(KEY_PARALLEL_LEVEL, DEFAULT_PARALLEL.toLong()).toInt().coerceIn(1, 6)

    fun recordParallelLevel(level: Int) = store.putLong(KEY_PARALLEL_LEVEL, level.coerceIn(1, 6).toLong())

    /** True while [strategy] is serving its 7-day ban after a runtime mismatch. */
    fun isBanned(strategy: SplitStrategy): Boolean = clock() < store.getLong(KEY_BAN_PREFIX + strategy.id, 0L)

    /** Separators the self-test may still try. */
    fun allowedStrategies(): List<SplitStrategy> = SplitStrategy.values().filterNot(::isBanned)

    /** True while joined mode is off altogether (2 mismatches this session, or every separator banned). */
    fun isJoinedBlocked(): Boolean = sessionBlocked || allowedStrategies().isEmpty()

    private fun storedChoiceValid(): Boolean =
        store.getString(KEY_CHOICE, "").isNotEmpty() &&
            store.getString(KEY_CHOICE_DEVICE, "") == deviceKey &&
            clock() - store.getLong(KEY_CHOICE_AT, 0L) < RETEST_MS

    /** The proven joined strategy to use right now, or null = parallel per-block calls. */
    fun current(): SplitStrategy? {
        if (isJoinedBlocked() || !storedChoiceValid()) return null
        return SplitStrategy.fromId(store.getString(KEY_CHOICE, ""))?.takeUnless(::isBanned)
    }

    /** True when the tiny on-device self-test should (re)run. Never while joined is blocked. */
    fun needsSelfTest(): Boolean = !isJoinedBlocked() && !storedChoiceValid()

    /** Store the outcome of a self-test/benchmark. [strategy] null = parallel won. */
    fun recordChoice(strategy: SplitStrategy?, why: String) {
        // A separator that failed on a real page is never re-adopted while its ban runs.
        store.putString(KEY_CHOICE, strategy?.takeUnless(::isBanned)?.id ?: PARALLEL)
        store.putString(KEY_CHOICE_DEVICE, deviceKey)
        store.putLong(KEY_CHOICE_AT, clock())
        store.putString(KEY_CHOICE_WHY, why.take(200))
    }

    /** A joined call did not map back: ban this separator for 7 days and re-prove another. */
    fun noteJoinedFailure(strategy: SplitStrategy, detail: String) {
        sessionFailures++
        if (sessionFailures >= MAX_SESSION_FAILURES) sessionBlocked = true
        store.putLong(KEY_BAN_PREFIX + strategy.id, clock() + BLOCK_MS)
        store.putString(KEY_BLOCK_WHY, "${strategy.label}: $detail".take(200))
        store.putString(KEY_CHOICE, "")
    }

    /** One line for the pipeline trace / reports. */
    fun describe(): String {
        val banned = SplitStrategy.values().filter(::isBanned)
        val bannedNote = if (banned.isEmpty()) "" else
            "; banned 7 d: ${banned.joinToString(" ") { it.label.trim() }} — last: ${store.getString(KEY_BLOCK_WHY, "?")}"
        return when {
            sessionBlocked ->
                "parallel x${parallelLevel()} (joined disabled until restart after $sessionFailures mismatches$bannedNote)"
            banned.size == SplitStrategy.values().size ->
                "parallel x${parallelLevel()} (joined disabled — every separator failed on a real page$bannedNote)"
            !storedChoiceValid() -> "parallel x${parallelLevel()} (joined not proven on this device yet — self-test pending$bannedNote)"
            else -> {
                val s = SplitStrategy.fromId(store.getString(KEY_CHOICE, ""))
                val why = store.getString(KEY_CHOICE_WHY, "")
if (s == null) "parallel x${parallelLevel()} ($why$bannedNote)" else "joined ${s.label} ($why$bannedNote)"
            }
        }
    }

    companion object {
        const val PARALLEL = "parallel"
        /**
         * Redmi 13C benchmark (20 bubbles): x2 950 ms · sequential 1022 · x3 1254 · x4 1624 —
         * ML Kit does not scale with more callers, so 2 is the default (was 3).
         */
        const val DEFAULT_PARALLEL = 2
        const val RULES_VERSION = 3L
        const val MAX_SESSION_FAILURES = 2
        const val KEY_BAN_PREFIX = "batch_ban_until_"
        const val KEY_RULES = "batch_rules_version"
        const val KEY_PARALLEL_LEVEL = "batch_parallel_level"
        const val DAY_MS = 24L * 60 * 60 * 1000
        const val BLOCK_MS = 7 * DAY_MS
        /** A stored choice is re-proven after this long (ML Kit models update silently). */
        const val RETEST_MS = 7 * DAY_MS
        const val KEY_CHOICE = "batch_choice"
        const val KEY_CHOICE_DEVICE = "batch_choice_device"
        const val KEY_CHOICE_AT = "batch_choice_at"
        const val KEY_CHOICE_WHY = "batch_choice_why"
        const val KEY_BLOCK_UNTIL = "batch_joined_blocked_until"
        const val KEY_BLOCK_WHY = "batch_joined_blocked_why"
    }
}

/** Process-wide policy instance (attached by TranslatorFactory, which owns the Context). */
object BatchPolicyHolder {
    @Volatile
    var instance: BatchPolicy? = null
}
