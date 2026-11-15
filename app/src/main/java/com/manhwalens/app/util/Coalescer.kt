package com.manhwalens.app.util

/**
 * Keeps only the LATEST submitted value and applies it exactly once per flush
 * (pure JVM — unit-testable with a manual dispatcher).
 *
 * Why: the live loop can flip the lens-button status several times per second;
 * without coalescing, a slow apply path would either queue stale intermediate
 * states (visual flicker + wasted layout passes) or apply out of order.
 *
 * Usage: [submit] stores the newest value; the first submit schedules ONE
 * dispatch through [dispatch]; whoever drains the queue applies only the final
 * value seen at drain time.
 */
class Coalescer<T>(private val dispatch: (() -> Unit) -> Unit) {

    private val lock = Any()
    private var latest: T? = null
    private var queued = false

    fun submit(value: T, apply: (T) -> Unit) {
        val needDispatch: Boolean
        synchronized(lock) {
            latest = value
            needDispatch = !queued
            if (needDispatch) queued = true
        }
        if (needDispatch) {
            dispatch {
                val v = synchronized(lock) {
                    val drained = latest
                    latest = null
                    queued = false
                    drained
                }
                if (v != null) apply(v)
            }
        }
    }
}
