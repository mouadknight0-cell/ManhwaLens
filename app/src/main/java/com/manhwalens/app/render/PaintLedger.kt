package com.manhwalens.app.render

/**
 * Tracks progressive per-block painting ("patches painted = N of M").
 * Total [expected] is the full number of blocks planned for the page.
 * Thread-safe; ids are counted once even if the engine re-delivers a block.
 */
class PaintLedger(expected: Int) {

    val total: Int = expected.coerceAtLeast(0)
    private val painted = LinkedHashSet<Int>()

    /** Record one painted block; returns the new N. */
    @Synchronized
    fun record(id: Int): Int {
        painted += id
        return painted.size
    }

    @Synchronized
    fun count(): Int = painted.size

    @Synchronized
    fun isComplete(): Boolean = total > 0 && painted.size >= total

    @Synchronized
    fun line(): String = "patches painted = ${painted.size} of $total"
}
