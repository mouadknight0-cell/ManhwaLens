package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiCircuitTest {

    private class Harness(var nowMs: Long = 1_000_000L) {
        var storedUntil: Long = 0L
        var storedReason: String = ""
        fun circuit() = GeminiCircuit(
            disabledUntilMs = { storedUntil },
            disabledReasonStore = { storedReason },
            onDisable = { until, reason ->
                storedUntil = until
                storedReason = reason
            },
            onClear = {
                storedUntil = 0L
                storedReason = ""
            },
            clock = { nowMs }
        )
    }

    @Test
    fun closedByDefault() {
        val h = Harness()
        assertFalse(h.circuit().isOpen())
        assertEquals(0L, h.circuit().remainingMs())
    }

    @Test
    fun failureOpensForFiveMinutes() {
        val h = Harness()
        h.circuit().open("http 429")
        val circuit = h.circuit()
        assertTrue(circuit.isOpen())
        assertEquals("http 429", circuit.reason())
        assertEquals(GeminiCircuit.DEFAULT_WINDOW_MINUTES * 60_000L, circuit.remainingMs())
        assertEquals(5, circuit.remainingMinutesRoundedUp())
    }

    @Test
    fun windowExpiresAutomatically() {
        val h = Harness()
        h.circuit().open("timeout")
        h.nowMs += GeminiCircuit.DEFAULT_WINDOW_MINUTES * 60_000L + 1
        val circuit = h.circuit()
        assertFalse(circuit.isOpen())
        assertEquals(0L, circuit.remainingMs())
    }

    @Test
    fun clearReEnablesGeminiImmediately() {
        val h = Harness()
        h.circuit().open("http 404")
        h.circuit().clear()
        val circuit = h.circuit()
        assertFalse(circuit.isOpen())
        assertEquals(0L, circuit.remainingMs())
        assertEquals("", circuit.reason())
    }

    @Test
    fun openTruncatesLongReasons() {
        val h = Harness()
        h.circuit().open("x".repeat(500))
        assertTrue(h.circuit().reason().length <= 120)
    }
}
