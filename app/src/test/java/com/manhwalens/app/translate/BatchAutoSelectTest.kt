package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchAutoSelectTest {

    private val fake = FakeArabicEngine.translate

    /** Fake clock advanced by the engine: each call costs what [cost] says. */
    private class Clock { @Volatile var t = 0L }

    private fun engine(clock: Clock, cost: (String) -> Long, mangle: (String) -> String = { it }): (String) -> String =
        { input ->
            synchronized(clock) { clock.t += cost(input) }
            mangle(fake(input))
        }

    private val isJoined: (String) -> Boolean = { it.length > 40 } // 4 strings joined are > 40 chars

    @Test
    fun picksFastestStrategyThatMaps() {
        val clock = Clock()
        // PIPE is the cheapest joined call; single per-block calls are expensive.
        val e = engine(clock, cost = { input ->
            when {
                !isJoined(input) -> 50
                input.contains(" | ") -> 10
                else -> 30
            }
        })
        val choice = BatchAutoSelect.run(e, clock = { clock.t })
        assertEquals(SplitStrategy.PIPE, choice.strategy)
        assertTrue(choice.trials.all { it.ok })
        assertEquals(SplitStrategy.values().size, choice.trials.size)
    }

    @Test
    fun skipsStrategiesThatDoNotMap_evenIfFaster() {
        val clock = Clock()
        // "@@@" is fastest but the model eats it; "###" is next and survives.
        val e = engine(
            clock,
            cost = { input ->
                when {
                    !isJoined(input) -> 50
                    input.contains("@@@") -> 1
                    input.contains("###") -> 10
                    else -> 30
                }
            },
            mangle = { it.replace("\n@@@\n", " ") }
        )
        val choice = BatchAutoSelect.run(e, clock = { clock.t })
        assertEquals(SplitStrategy.HASH, choice.strategy)
        val at = choice.trials.first { it.strategy == SplitStrategy.AT }
        assertFalse(at.ok)
        assertTrue(at.mismatched >= 1)
    }

    @Test
    fun nonePasses_fallsBackToParallel() {
        val clock = Clock()
        // Model flattens every reply to one line with no markers at all.
        val e = engine(clock, cost = { 10 }, mangle = { it.replace(Regex("[^\\p{L}\\p{N} .?,']+"), " ") })
        val choice = BatchAutoSelect.run(e, clock = { clock.t })
        assertNull(choice.strategy)
        assertTrue(choice.trials.none { it.ok })
        assertTrue(choice.why.contains("parallel"))
    }

    @Test
    fun parallelStays_whenJoinedIsNotFaster() {
        val clock = Clock()
        // Joined calls are slow, per-block calls nearly free.
        val e = engine(clock, cost = { input -> if (isJoined(input)) 500 else 1 })
        val choice = BatchAutoSelect.run(e, clock = { clock.t })
        assertNull(choice.strategy)
        assertTrue(choice.trials.all { it.ok })
        assertTrue(choice.why.startsWith("parallel"))
    }

    @Test
    fun separatorThatBreaksAtPageSize_isRejected_nextFastestWins() {
        val clock = Clock()
        // "@@@" is fastest and survives 4 short strings, but loses its markers on a
        // 10-bubble page (the Redmi 13C result). "###" is next and holds.
        val e = engine(
            clock,
            cost = { input ->
                when {
                    !isJoined(input) -> 50
                    input.contains("@@@") -> 1
                    input.contains("###") -> 10
                    else -> 30
                }
            },
            mangle = { out -> if (Regex("@@@").findAll(out).count() >= 9) out.replace("\n@@@\n", " ") else out }
        )
        val choice = BatchAutoSelect.run(e, clock = { clock.t })
        assertEquals(SplitStrategy.HASH, choice.strategy)
        val at = choice.trials.first { it.strategy == SplitStrategy.AT }
        assertFalse(at.ok)
        assertTrue(at.reason.contains("page-size"))
    }

    @Test
    fun everySeparatorBreaksAtPageSize_meansParallel() {
        val clock = Clock()
        val e = engine(
            clock,
            cost = { input -> if (isJoined(input)) 5 else 50 },
            // any reply longer than the 4-string self-test gets flattened to one line
            mangle = { out -> if (out.length > 200) out.replace(Regex("[^\\p{L}\\p{N} .?,'…!]+"), " ") else out }
        )
        val choice = BatchAutoSelect.run(e, clock = { clock.t })
        assertNull(choice.strategy)
        assertTrue(choice.why.contains("page-size"))
    }

    @Test
    fun strategyMustPassEveryRun() {
        val clock = Clock()
        var newlineCalls = 0
        // "\n" maps on the first run and breaks on the second.
        val e: (String) -> String = { input ->
            synchronized(clock) { clock.t += if (isJoined(input)) 5 else 50 }
            val out = fake(input)
            val plainNewline = isJoined(input) && input.contains("\n") && !input.contains("\n\n") &&
                listOf("@@@", "###", "[1]", "<1>", "•").none { input.contains(it) }
            if (plainNewline && ++newlineCalls == 2) out.replace("\n", " ") else out
        }
        val choice = BatchAutoSelect.run(e, clock = { clock.t })
        assertFalse(choice.trials.first { it.strategy == SplitStrategy.NEWLINE }.ok)
        assertTrue(choice.strategy != SplitStrategy.NEWLINE)
    }

    @Test
    fun engineErrors_areCaptured_andMeanParallel() {
        val boom: (String) -> String = { throw IllegalStateException("engine down") }
        val choice = BatchAutoSelect.run(boom, clock = { 0L })
        assertNull(choice.strategy)
        assertTrue(choice.trials.all { !it.ok && it.reason.contains("error") })
        assertEquals(-1L, choice.parallelMs)
    }

    @Test
    fun choiceFeedsThePolicy() {
        val clock = Clock()
        val e = engine(clock, cost = { input -> if (isJoined(input)) 10 else 50 })
        val choice = BatchAutoSelect.run(e, clock = { clock.t })
        val policy = BatchPolicy(MapBatchStore(), "dev") { 0L }
        assertTrue(policy.needsSelfTest())
        policy.recordChoice(choice.strategy, choice.why)
        assertEquals(choice.strategy, policy.current())
        assertFalse(policy.needsSelfTest())
    }
}
