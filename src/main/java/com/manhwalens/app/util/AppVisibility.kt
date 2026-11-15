package com.manhwalens.app.util

import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide "any ManhwaLens activity is started" counter (pure JVM — unit-testable).
 *
 * Used by Live mode to pause while the user is inside the app itself (our own screens
 * are not translatable pages, and the patch overlay must not float above them).
 * [onActivityStarted]/[onActivityStopped] return true exactly on the 0→1 / 1→0 transitions.
 *
 * v0.9.5 (Part 1a): the counter can only ever be as correct as the lifecycle callbacks it
 * receives. An activity that is destroyed without `onActivityStopped` (HyperOS process
 * restore, a translucent consent activity killed mid-transition, a crash inside onStop)
 * leaves it stuck above 0 and Live stays paused forever with no visible reason. [reconcile]
 * is the safety valve: the caller passes the number of activities it REALLY sees started
 * and the counter is corrected — see `LiveGates.GATE_APP_VISIBLE`.
 */
class AppVisibility {

    private val started = AtomicInteger(0)

    /** @return true when the app just became visible (first activity started). */
    fun onActivityStarted(): Boolean = started.incrementAndGet() == 1

    /** @return true when the app just became fully hidden (last activity stopped). Never spuriously true. */
    fun onActivityStopped(): Boolean {
        while (true) {
            val current = started.get()
            if (current <= 0) return false
            if (started.compareAndSet(current, current - 1)) return current == 1
        }
    }

    fun isVisible(): Boolean = started.get() > 0

    /** Raw counter value (for the gate line and the self-check report). */
    fun value(): Int = started.get()

    /** True when the counter claims "visible" while nothing is actually started. */
    fun isStuck(actualStarted: Int): Boolean = started.get() > 0 && actualStarted <= 0

    /**
     * Correct the counter against ground truth.
     * @param actualStarted how many ManhwaLens activities the tracker really sees started
     * @return the counter value AFTER the correction (a change means it was stuck/desynced)
     */
    fun reconcile(actualStarted: Int): Int {
        val target = actualStarted.coerceAtLeast(0)
        while (true) {
            val current = started.get()
            if (current == target) return current
            if (started.compareAndSet(current, target)) return target
        }
    }

    /** Hard reset (used when the tracker itself was re-registered). */
    fun forceReset(): Int = started.getAndSet(0)

    companion object {
        /** Shared process-wide instance consulted by the service's live loop. */
        val shared = AppVisibility()
    }
}
