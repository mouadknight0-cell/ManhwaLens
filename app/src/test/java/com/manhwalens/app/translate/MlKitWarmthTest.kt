package com.manhwalens.app.translate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MlKitWarmthTest {

    @Test
    fun firstCallIsCold_laterCallsAreWarm() {
        MlKitWarmth.resetForTests()
        assertFalse(MlKitWarmth.isWarm())
        assertTrue(MlKitWarmth.describe(0L).contains("NOT pre-warmed"))

        MlKitWarmth.note(412, "service start", nowMs = 1_000)
        assertTrue(MlKitWarmth.isWarm())
        assertTrue(MlKitWarmth.describe(2_000).contains("cold 412 ms (service start)"))

        MlKitWarmth.note(38, "screen on", nowMs = 60_000)
        val line = MlKitWarmth.describe(65_000)
        assertTrue(line, line.contains("cold 412 ms"))
        assertTrue(line, line.contains("warm 38 ms (screen on)"))
        assertTrue(line, line.contains("5s ago"))
        assertTrue(line, line.contains("warm-ups 2"))
        MlKitWarmth.resetForTests()
    }
}
