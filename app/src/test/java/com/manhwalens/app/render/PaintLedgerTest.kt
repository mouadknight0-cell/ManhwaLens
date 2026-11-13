package com.manhwalens.app.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaintLedgerTest {

    @Test
    fun startsAtZeroOfTotal() {
        val ledger = PaintLedger(5)
        assertEquals("patches painted = 0 of 5", ledger.line())
        assertEquals(0, ledger.count())
        assertFalse(ledger.isComplete())
    }

    @Test
    fun recordsEveryBlockIdOnce() {
        val ledger = PaintLedger(3)
        assertEquals(1, ledger.record(7))
        assertEquals(2, ledger.record(9))
        assertEquals(2, ledger.record(7)) // duplicate — still 2
        assertEquals("patches painted = 2 of 3", ledger.line())
        assertFalse(ledger.isComplete())
        assertEquals(3, ledger.record(11))
        assertTrue(ledger.isComplete())
        assertEquals("patches painted = 3 of 3", ledger.line())
    }

    @Test
    fun zeroTotalNeverCompletes() {
        val ledger = PaintLedger(0)
        assertEquals(1, ledger.record(1))
        assertFalse(ledger.isComplete())
        assertEquals("patches painted = 1 of 0", ledger.line())
    }

    @Test
    fun paintingBeyondTotalAccumulatesButIsComplete() {
        val ledger = PaintLedger(2)
        ledger.record(1)
        ledger.record(2)
        ledger.record(3)
        assertTrue(ledger.isComplete())
        assertEquals("patches painted = 3 of 2", ledger.line())
    }
}
