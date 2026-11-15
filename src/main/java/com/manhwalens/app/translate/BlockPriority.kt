package com.manhwalens.app.translate

/**
 * Priority ordering for progressive display (Part C):
 *   1) SHORT bubbles (< 80 chars) first — they complete fastest;
 *   2) then LARGEST bubble area first — most pixels of reading, first served;
 *   3) then MOST CENTRAL first — frame center reads like a story anchor.
 *
 * Pure and JVM-testable: callers pass the geometry explicitly.
 */
object BlockPriority {

    const val SHORT_TEXT = 80

    fun isShort(text: String): Boolean = text.trim().length < SHORT_TEXT

    /**
     * @param texts   block text per index (same index space as [areas]/[distances])
     * @param areas   bubble area per index (px²)
     * @param centerDistances distance of each bubble center from the frame center (px)
     * @return indices into the input lists, highest priority first (stable for ties)
     */
    fun order(texts: List<String>, areas: List<Float>, centerDistances: List<Float>): List<Int> {
        require(texts.size == areas.size && areas.size == centerDistances.size)
        return (texts.indices).sortedWith(
            compareByDescending<Int> { isShort(texts[it]) }
                .thenByDescending { areas[it] }
                .thenBy { centerDistances[it] }
                .thenBy { it }
        )
    }

    /** Convenience for rectangular bubble bounds. */
    fun orderRects(
        texts: List<String>,
        left: List<Float>, top: List<Float>, right: List<Float>, bottom: List<Float>,
        frameWidth: Int, frameHeight: Int
    ): List<Int> {
        val cx = frameWidth / 2f
        val cy = frameHeight / 2f
        val areas = texts.indices.map { i -> (right[i] - left[i]) * (bottom[i] - top[i]) }
        val dists = texts.indices.map { i ->
            val bx = (left[i] + right[i]) / 2f - cx
            val by = (top[i] + bottom[i]) / 2f - cy
            kotlin.math.sqrt((bx * bx + by * by).toDouble()).toFloat()
        }
        return order(texts, areas, dists)
    }
}
