package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchPolicyTest {

    private var now = 1_000_000L
    private val store = MapBatchStore()
    private fun policy(device: String = "dev-A") = BatchPolicy(store, device) { now }

    @Test
    fun default_isParallel_andWantsSelfTest() {
        val p = policy()
        assertNull(p.current())
        assertTrue(p.needsSelfTest())
        assertTrue(p.describe().startsWith("parallel"))
    }

    @Test
    fun provenChoice_isUsed_andPersistsAcrossProcesses() {
        policy().recordChoice(SplitStrategy.NEWLINE, "fastest")
        val restarted = policy()
        assertEquals(SplitStrategy.NEWLINE, restarted.current())
        assertFalse(restarted.needsSelfTest())
        assertTrue(restarted.describe().contains("joined"))
    }

    @Test
    fun parallelVerdict_isRemembered_noRetestEveryStart() {
        policy().recordChoice(null, "nothing mapped")
        val restarted = policy()
        assertNull(restarted.current())
        assertFalse(restarted.needsSelfTest())
    }

    @Test
    fun oneFailure_bansThatSeparatorOnly_secondFailureEndsTheSession() {
        val p = policy()
        p.recordChoice(SplitStrategy.PIPE, "fastest")
        p.noteJoinedFailure(SplitStrategy.PIPE, "count 3 != 4")
        assertNull(p.current())
        assertTrue(p.isBanned(SplitStrategy.PIPE))
        assertFalse(p.isJoinedBlocked())
        assertTrue("self-test re-runs over the other separators", p.needsSelfTest())
        assertFalse(SplitStrategy.PIPE in p.allowedStrategies())
        assertTrue(SplitStrategy.BULLET in p.allowedStrategies())
        // The banned separator can not be re-adopted, another one can.
        p.recordChoice(SplitStrategy.PIPE, "benchmark")
        assertNull(p.current())
        p.recordChoice(SplitStrategy.BULLET, "self-test")
        assertEquals(SplitStrategy.BULLET, p.current())
        assertTrue(p.describe().contains("banned 7 d: |"))
        // Second mismatch in the same session: joined is off until restart.
        p.noteJoinedFailure(SplitStrategy.BULLET, "count 2 != 3")
        assertTrue(p.isJoinedBlocked())
        assertFalse(p.needsSelfTest())
        p.recordChoice(SplitStrategy.HASH, "benchmark")
        assertNull(p.current())
    }

    @Test
    fun failure_isRememberedFor7Days_acrossRestarts() {
        policy().apply {
            recordChoice(SplitStrategy.AT, "fastest")
            noteJoinedFailure(SplitStrategy.AT, "mismatch")
        }
        now += 6 * BatchPolicy.DAY_MS
        val day6 = policy() // new process: session flag gone, prefs remember
        assertTrue(day6.isBanned(SplitStrategy.AT))
        assertNull(day6.current())
        assertTrue(day6.describe().contains("banned"))

        now += 1 * BatchPolicy.DAY_MS + 1
        val day7 = policy()
        assertFalse(day7.isBanned(SplitStrategy.AT))
        assertFalse(day7.isJoinedBlocked())
        assertNull("the failed choice was forgotten — must be re-proven", day7.current())
        assertTrue(day7.needsSelfTest())
    }

    @Test
    fun choice_isPerDevice() {
        policy("dev-A").recordChoice(SplitStrategy.PIPE, "fastest")
        val other = policy("dev-B") // e.g. restored backup on another phone / OS update
        assertNull(other.current())
        assertTrue(other.needsSelfTest())
    }

    @Test
    fun choice_isReprovenAfter7Days() {
        policy().recordChoice(SplitStrategy.PIPE, "fastest")
        now += BatchPolicy.RETEST_MS + 1
        val later = policy()
        assertNull(later.current())
        assertTrue(later.needsSelfTest())
    }

    @Test
    fun banMadeUnderOldRules_isLifted_onUpgrade() {
        // State left behind by v0.9.9/0.9.10: a 7-day ban caused by the old "no Arabic letters" rule.
        store.longs[BatchPolicy.KEY_BLOCK_UNTIL] = now + 6 * BatchPolicy.DAY_MS
        store.strings[BatchPolicy.KEY_BLOCK_WHY] = "•: mismatch(1): #2 no Arabic letters"
        val p = policy()
        assertFalse(p.isJoinedBlocked())
        assertTrue(p.needsSelfTest())
        // …but a ban made under the CURRENT rules survives a restart.
        p.recordChoice(SplitStrategy.BULLET, "x")
        p.noteJoinedFailure(SplitStrategy.BULLET, "count 3 != 5")
        assertTrue(policy().isBanned(SplitStrategy.BULLET))
    }

    @Test
    fun upgradeToRules3_dropsANoisyParallelLevel_andOldBans() {
        store.longs[BatchPolicy.KEY_RULES] = 2L
        store.longs[BatchPolicy.KEY_PARALLEL_LEVEL] = 6L
        store.longs[BatchPolicy.KEY_BLOCK_UNTIL] = now + 6 * BatchPolicy.DAY_MS
        val p = policy()
        assertEquals(2, p.parallelLevel())
        assertFalse(p.isJoinedBlocked())
        assertTrue(p.needsSelfTest())
    }

    @Test
    fun parallelLevel_defaultsTo2_andFollowsTheBenchmark() {
        val p = policy()
        assertEquals(2, p.parallelLevel())
        p.recordParallelLevel(4)
        assertEquals(4, policy().parallelLevel())
        p.recordParallelLevel(99)
        assertEquals(6, p.parallelLevel())
        assertTrue(p.describe().contains("x6"))
    }

    /** The runtime loop the ML Kit engine runs: joined only via the policy, parallel otherwise. */
    @Test
    fun noJoinedAttempt_afterOneFailure() {
        val p = policy()
        p.recordChoice(SplitStrategy.AT, "self-test")
        val sources = listOf("Hello there.", "Where are you going?", "Stay behind me.")
        var joinedCalls = 0
        var parallelPages = 0
        // Engine that eats "@@@" on real pages (what the Redmi 13C does).
        val engine: (String) -> String = { FakeArabicEngine.translate(it).replace("\n@@@\n", " ") }

        fun translatePage() {
            val s = p.current()
            if (s != null) {
                joinedCalls++
                val v = BatchSplitter.validate(s, sources, engine(BatchSplitter.join(s, sources)))
                if (v.ok) return
                p.noteJoinedFailure(s, v.reason)
            }
            parallelPages++
        }

        repeat(5) { translatePage() }
        assertEquals("exactly one page ever pays for a failed joined attempt", 1, joinedCalls)
        assertEquals(5, parallelPages)

        // …and a restarted app within 7 days pays for none.
        val restarted = policy()
        assertNull(restarted.current())
    }
}
