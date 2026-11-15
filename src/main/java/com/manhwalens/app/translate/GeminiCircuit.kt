package com.manhwalens.app.translate

/**
 * Circuit breaker for Gemini. After a failure (invalid key, rate-limit, 404,
 * timeout, parse error…) the engine is paused for [DEFAULT_WINDOW_MINUTES] and
 * the factory builds the on-device ML Kit translator instead of trying Gemini
 * first. The user can force a retry from Settings ("Retry Gemini" clears
 * the window).
 *
 * Pure Kotlin + injected clock/storage so the policy is unit-testable on the
 * JVM. TranslationPrefs hosts the Android-backed instance.
 */
class GeminiCircuit(
    private val disabledUntilMs: () -> Long,
    private val disabledReasonStore: () -> String,
    private val onDisable: (untilMs: Long, reason: String) -> Unit,
    private val onClear: () -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    fun isOpen(): Boolean = clock() < disabledUntilMs()

    fun remainingMs(): Long = (disabledUntilMs() - clock()).coerceAtLeast(0L)

    fun remainingMinutesRoundedUp(): Int = ((remainingMs() + 59_999L) / 60_000L).toInt().coerceAtLeast(1)

    fun reason(): String = disabledReasonStore()

    /** Open the circuit for [DEFAULT_WINDOW_MINUTES] following a Gemini failure. */
    fun open(reason: String) = onDisable(clock() + DEFAULT_WINDOW_MINUTES * 60_000L, reason.take(120))

    /** Pause for a custom window — used for transient failures (timeout, network). */
    fun open(reason: String, windowMs: Long) = onDisable(clock() + windowMs, reason.take(120))

    /** User pressed "Retry Gemini" — close the circuit immediately. */
    fun clear() = onClear()

    companion object {
        const val DEFAULT_WINDOW_MINUTES = 5
    }
}
