package com.manhwalens.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoalescerTest {

    @Test
    fun coalescesToLatestValueWhenDispatchDeferred() {
        val queue = mutableListOf<() -> Unit>()
        val applied = mutableListOf<String>()
        val c = Coalescer<String> { block -> queue += block }

        c.submit("a") { applied += it }
        c.submit("b") { applied += it }
        c.submit("c") { applied += it }

        assertEquals(1, queue.size) // only one dispatch scheduled for the whole burst
        queue.forEach { it() }
        assertEquals(listOf("c"), applied) // only the newest value is applied
    }

    @Test
    fun secondWaveQueuesAfterFirstDrains() {
        val queue = mutableListOf<() -> Unit>()
        val applied = mutableListOf<String>()
        val c = Coalescer<String> { block -> queue += block }

        c.submit("a") { applied += it }
        queue.forEach { it() }
        c.submit("b") { applied += it }
        assertEquals(listOf("a"), applied)
        queue.forEach { it() }
        assertEquals(listOf("a", "b"), applied)
    }

    @Test
    fun inlineDispatchAppliesOnSubmit() {
        val applied = mutableListOf<Int>()
        val c = Coalescer<Int> { block -> block() } // inline dispatcher (main-thread semantics)
        c.submit(1) { applied += it }
        c.submit(2) { applied += it }
        assertEquals(listOf(1, 2), applied)
    }

    @Test
    fun burstFromBackgroundThreadsAppliesNewestWithoutCrashing() {
        val queue = java.util.Collections.synchronizedList(mutableListOf<() -> Unit>())
        val applied = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val c = Coalescer<Int> { block -> queue += block }

        val t = Thread { repeat(50) { i -> c.submit(i) { v -> applied += v } } }
        t.start()
        t.join()

        assertEquals(1, queue.size)          // the whole burst coalesced into one dispatch
        queue.forEach { it() }
        assertEquals(listOf(49), applied)    // newest value won
    }

    @Test
    fun drainLeavesCoalescerReadyForNextRound() {
        val queue = mutableListOf<() -> Unit>()
        val applied = mutableListOf<Int>()
        val c = Coalescer<Int> { block -> queue += block }
        c.submit(1) { applied += it }
        queue.forEach { it() }
        queue.clear()
        c.submit(2) { applied += it }
        queue.forEach { it() }
        assertEquals(listOf(1, 2), applied)
    }
}
