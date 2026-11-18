package com.manhwalens.app.util

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StartupGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun guard(name: String = "startup_crash_counter") =
        StartupGuard(tmp.newFile(name).also { it.delete() })

    @Test
    fun shortRunsWithoutExceptionsDoNotIncrementOrEnterSafeMode() {
        val guard = guard()

        assertEquals(0, guard.registerStart())
        assertEquals(0, guard.registerStart())
        assertEquals(0, guard.currentCount())
        assertFalse(StartupGuard.isSafeMode(guard.currentCount()))
    }

    @Test
    fun threeRecordedCrashesEnterSafeModeOnNextStart() {
        val guard = guard()

        repeat(3) { run ->
            guard.registerStart()
            guard.recordExceptionSummary(IllegalStateException("fatal run ${run + 1}"))
        }

        assertEquals(3, guard.registerStart())
        assertTrue(StartupGuard.isSafeMode(guard.currentCount()))
    }

    @Test
    fun oneFatalExceptionCountsOnlyOnceForThatRun() {
        val guard = guard()
        guard.registerStart()

        guard.recordExceptionSummary(IllegalStateException("first"))
        guard.recordExceptionSummary(IllegalArgumentException("duplicate callback"))

        assertEquals(1, guard.currentCount())
    }

    @Test
    fun healthyRunResetsCrashStreak() {
        val guard = guard()
        guard.registerStart()
        guard.recordExceptionSummary(IllegalStateException("fatal"))
        assertEquals(1, guard.currentCount())

        guard.registerStart()
        guard.markHealthy()

        assertEquals(0, guard.currentCount())
        assertFalse(StartupGuard.isSafeMode(guard.currentCount()))
    }

    @Test
    fun legacyShortRunCounterIsDiscardedOnFirstStartWithNewPolicy() {
        val counter = tmp.newFile("startup_crash_counter")
        counter.writeText("2")
        val guard = StartupGuard(counter)

        assertEquals(0, guard.registerStart())
        assertEquals(0, guard.currentCount())
    }

    @Test
    fun corruptCounterFileIsTreatedAsZero() {
        val counter = tmp.newFile("startup_crash_counter")
        counter.writeText("not a number")
        val guard = StartupGuard(counter)

        assertEquals(0, guard.currentCount())
        assertEquals(0, guard.registerStart())
    }

    @Test
    fun lastExceptionSummaryIncludesClassMessageAndFrames() {
        val guard = guard()
        guard.registerStart()
        guard.recordExceptionSummary(IllegalStateException("test crash"))

        val summary = guard.lastExceptionSummary()
        assertTrue(summary.contains("java.lang.IllegalStateException"))
        assertTrue(summary.contains("test crash"))
        assertTrue(summary.contains("lastExceptionSummaryIncludesClassMessageAndFrames"))
        assertTrue(guard.reportLine().contains("consecutive=1"))
        assertTrue(guard.reportLine().contains("safe_mode=false"))
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
    fun safeModeThresholdIsExactlyThree() {
        assertFalse(StartupGuard.isSafeMode(0))
        assertFalse(StartupGuard.isSafeMode(1))
        assertFalse(StartupGuard.isSafeMode(2))
        assertTrue(StartupGuard.isSafeMode(3))
        assertTrue(StartupGuard.isSafeMode(4))
    }
}
