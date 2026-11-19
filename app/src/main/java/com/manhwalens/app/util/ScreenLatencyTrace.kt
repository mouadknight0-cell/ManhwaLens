package com.manhwalens.app.util

/** Per-screen monotonic milestones. No text, image data or credentials are recorded.
 * Draw means Canvas submission, NOT a hardware presentation fence.
 * Missing milestones stay missing: failed screens must never look like fast successes.
 */
class ScreenLatencyTrace(
    val id: Long,
    private val clock: () -> Long,
    private val startedAtMs: Long = clock(),
    private val emit: (String) -> Unit
) {
    enum class Event { T0, T1, T2, T3, T4, T5, T6, T7 }
    private val times = linkedMapOf<Event, Long>()
    private var expected = emptySet<Int>()
    private val drawn = hashSetOf<Int>()
    private val translated = hashSetOf<Int>()

    init {
        times[Event.T0] = startedAtMs
        emit("screen_latency id=$id event=T0 ms=$startedAtMs")
    }

    @Synchronized fun mark(event: Event) {
        if (event in times) return
        val now = clock()
        times[event] = now
        emit("screen_latency id=$id event=$event ms=$now")
    }

    @Synchronized fun expect(ids: Collection<Int>) {
        expected = ids.toSet()
        emit("screen_latency id=$id event=regions count=${expected.size}")
    }

    @Synchronized fun translated(id: Int) {
        if (id in expected && translated.add(id))
            emit("screen_latency id=${this.id} event=translated count=${translated.size}")
    }

    @Synchronized fun translationFinished() {
        emit("screen_latency id=$id event=translation_finished count=${translated.size}")
    }

    @Synchronized fun drawn(id: Int) {
        if (id !in expected || !drawn.add(id)) return
        mark(Event.T6)
        emit("screen_latency id=${this.id} event=drawn count=${drawn.size}")
        if (expected.isNotEmpty() && drawn.containsAll(expected)) mark(Event.T7)
    }

    @Synchronized fun snapshot(): Map<Event, Long> = times.toMap()
}
