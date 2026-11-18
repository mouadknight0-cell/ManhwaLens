package com.manhwalens.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StartupGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun guard(name: String = "startup_crash_counter") =
        StartupGuard(tmp.newFile(name).also { it.delete() })

    @Test
    fun firstStartCountsOne() {
        val g = guard()
        assertEquals(1, g.registerStart())
        assertEquals(1, g.currentCount())
    }

    @Test
    fun secondConsecutiveUnhealthyStartTriggersSafeMode() {
        val g = guard()
        g.registerStart()                 // crashed before 5 s
        val n = g.registerStart()         // app did not survive again
        assertEquals(2, n)
        assertTrue(StartupGuard.isSafeMode(n))
    }

    @Test
    fun healthyRunResetsTheStreak() {
        val g = guard()
        g.registerStart()
        g.markHealthy()                   // 5 s of healthy running
        assertEquals(0, g.currentCount())
        assertEquals(1, g.registerStart()) // streak starts over
        assertFalse(StartupGuard.isSafeMode(1))
    }

    @Test
    fun corruptCounterFileIsTreatedAsZero() {
        val f = tmp.newFile("startup_crash_counter")
        f.writeText("!!! not a number !!!")
        val g = StartupGuard(f)
        assertEquals(0, g.currentCount())
        assertEquals(1, g.registerStart())
    }

    @Test
    fun lastExceptionSummaryIncludesClassMessageAndFrames() {
        val g = guard()
        g.registerStart()
        g.recordExceptionSummary(IllegalStateException("test crash"))
        val summary = g.lastExceptionSummary()
        assertTrue(summary.contains("java.lang.IllegalStateException"))
        assertTrue(summary.contains("test crash"))
        assertTrue(summary.contains("lastExceptionSummaryIncludesClassMessageAndFrames"))
        assertTrue(g.reportLine().contains("consecutive=1"))
    }

    @Test
    fun uncaughtHandlerWritesFullStackBeforeDelegating() {
        val log = File(tmp.root, "startup-crashes.log")
        val delegated = AtomicBoolean(false)
        val previous = Thread.UncaughtExceptionHandler { _, _ -> delegated.set(true) }
        val handler = StartupExceptionHandler(log, previous)
        handler.uncaughtException(Thread.currentThread(), IllegalStateException("diagnostic marker"))
        val written = log.readText()
        assertTrue(written.contains("java.lang.IllegalStateException"))
        assertTrue(written.contains("diagnostic marker"))
        assertTrue(written.contains("uncaughtHandlerWritesFullStackBeforeDelegating"))
        assertTrue(delegated.get())
    }

    @Test
    fun thresholdIsExactlyTwo() {
        assertFalse(StartupGuard.isSafeMode(0))
        assertFalse(StartupGuard.isSafeMode(1))
        assertTrue(StartupGuard.isSafeMode(2))
        assertTrue(StartupGuard.isSafeMode(3))
    }
}
