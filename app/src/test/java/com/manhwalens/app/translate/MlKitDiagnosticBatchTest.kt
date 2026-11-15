package com.manhwalens.app.translate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class MlKitDiagnosticBatchTest {
    @Test
    fun usesJoinedStrategyOnlyWhenEverySegmentMaps() {
        val calls = mutableListOf<String>()
        val result = MlKitDiagnosticBatch.translate(
            inputs = listOf("Hello there", "Good morning"),
            parallelLevel = 2,
            joinedStrategy = SplitStrategy.AT
        ) { input ->
            calls += input
            "مرحبا بك\n@@@\nصباح الخير"
        }

        assertEquals("joined @@@", result.dispatch)
        assertEquals(listOf("مرحبا بك", "صباح الخير"), result.outputs)
        assertEquals(1, calls.size)
    }

    @Test
    fun invalidJoinedReplyFallsBackToParallelAndPreservesInputOrder() {
        val calls = mutableListOf<String>()
        val result = MlKitDiagnosticBatch.translate(
            inputs = listOf("Hello there", "Good morning"),
            parallelLevel = 2,
            joinedStrategy = SplitStrategy.AT
        ) { input ->
            synchronized(calls) { calls += input }
            if (input.contains("\n@@@\n")) "one response without the separator"
            else if (input == "Hello there") "مرحبا بك"
            else "صباح الخير"
        }

        assertEquals("parallel x2 (joined @@@ did not map; parallel fallback)", result.dispatch)
        assertEquals(listOf("مرحبا بك", "صباح الخير"), result.outputs)
        assertEquals(3, calls.size)
    }

    @Test
    fun parallelDispatchPreservesOriginalOrderAndHonoursConcurrencyLimit() {
        val active = AtomicInteger(0)
        val peak = AtomicInteger(0)
        val inputs = listOf("one", "two", "three", "four", "five", "six")
        val result = MlKitDiagnosticBatch.translate(
            inputs = inputs,
            parallelLevel = 2,
            joinedStrategy = null
        ) { input ->
            val now = active.incrementAndGet()
            peak.updateAndGet { old -> maxOf(old, now) }
            try {
                Thread.sleep(15)
                "ترجمة $input"
            } finally {
                active.decrementAndGet()
            }
        }

        assertEquals("parallel x2", result.dispatch)
        assertEquals(inputs.map { "ترجمة $it" }, result.outputs)
        assertTrue("parallelism must stay within the saved level", peak.get() <= 2)
    }

    @Test
    fun unsafeMarkerInSourceSkipsJoinedRequestWithoutChangingTheSource() {
        val calls = mutableListOf<String>()
        val result = MlKitDiagnosticBatch.translate(
            inputs = listOf("This | is text", "Another bubble"),
            parallelLevel = 2,
            joinedStrategy = SplitStrategy.PIPE
        ) { input ->
            synchronized(calls) { calls += input }
            "ترجمة مناسبة"
        }

        assertEquals("parallel x2 (joined skipped: marker in source)", result.dispatch)
        assertEquals(2, calls.size)
    }
}
