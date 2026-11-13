package com.manhwalens.app.ocr

import com.manhwalens.app.ocr.geometry.Rect2D
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Tunable knobs for the bubble-merging heuristic. */
data class BubbleMergeParams(
    /** Vertical gap between two lines may be at most this × taller line and still merge. */
    val maxVerticalGapRatio: Float = 0.9f,
    /** Horizontal overlap must be at least this × narrower line's width. */
    val minHorizontalOverlapRatio: Float = 0.2f,
    /**
     * Font-size guard against merging dialogue with SFX bursts:
     * min(h)/max(h) of the two lines must be at least this.
     */
    val minHeightRatio: Float = 0.40f,
    /**
     * Center guard: |cx difference| / max(w1, w2) must not exceed this.
     * Keeps same-column stacked lines together while separating two bubbles
     * sitting side by side.
     */
    val maxCenterDeltaRatio: Float = 0.75f
)

/** One merged block: lines that belong together, usually one speech bubble. */
data class BubbleBlock(
    val id: Int,
    val lines: List<DetectedLine>
) {
    val bounds: Rect2D get() = Rect2D.unionAll(lines.map { it.box })
    val text: String get() = lines.joinToString("\n") { it.text }
}

/**
 * Groups OCR lines into speech-bubble blocks.
 *
 * Strategy: sort lines in reading order (top → bottom, left → right), then
 * union-find over all pairs that satisfy the merge predicate. O(n²) is fine:
 * a dense manhwa page yields at most ~60 lines.
 *
 * The merge predicate compares the upper and lower line of a candidate pair:
 * vertical gap ratio + horizontal overlap ratio + font-size guard + center guard.
 */
object BubbleGrouper {

    fun group(lines: List<DetectedLine>, params: BubbleMergeParams = BubbleMergeParams()): List<BubbleBlock> {
        if (lines.isEmpty()) return emptyList()

        val sorted = lines.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        val n = sorted.size
        val parent = IntArray(n) { it }

        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) {
                parent[r] = parent[parent[r]] // path halving
                r = parent[r]
            }
            return r
        }

        fun union(i: Int, j: Int) {
            val ri = find(i)
            val rj = find(j)
            if (ri != rj) parent[max(ri, rj)] = min(ri, rj)
        }

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (shouldMerge(sorted[i], sorted[j], params)) union(i, j)
            }
        }

        val byRoot = LinkedHashMap<Int, MutableList<DetectedLine>>()
        for (i in 0 until n) {
            byRoot.getOrPut(find(i)) { mutableListOf() }.add(sorted[i])
        }

        return byRoot.values
            .map { group -> group.sortedWith(compareBy({ it.box.top }, { it.box.left })) }
            .sortedWith(compareBy({ it.minOf { line -> line.box.top } }, { it.minOf { line -> line.box.left } }))
            .mapIndexed { index, group -> BubbleBlock(id = index + 1, lines = group) }
    }

    fun shouldMerge(a: DetectedLine, b: DetectedLine, params: BubbleMergeParams = BubbleMergeParams()): Boolean {
        val upper = if (a.box.centerY <= b.box.centerY) a else b
        val lower = if (a.box.centerY <= b.box.centerY) b else a

        // Vertical gap (negative when overlapping).
        val vGap = lower.box.top - upper.box.bottom
        val refH = max(upper.box.height, lower.box.height)
        if (refH <= 0f) return false
        if (vGap > refH * params.maxVerticalGapRatio) return false

        // Horizontal overlap relative to the narrower line.
        val overlap = upper.box.horizontalOverlap(lower.box)
        val minW = min(upper.box.width, lower.box.width)
        if (minW <= 0f) return false
        if (overlap / minW < params.minHorizontalOverlapRatio) return false

        // Font size guard: SFX bursts are usually much taller than dialogue lines.
        val hRatio = min(upper.box.height, lower.box.height) / max(upper.box.height, lower.box.height)
        if (hRatio < params.minHeightRatio) return false

        // Center guard: stacked dialogue is usually (near-)centered on the bubble column.
        val wMax = max(upper.box.width, lower.box.width)
        if (wMax > 0f) {
            val cDelta = abs(upper.box.centerX - lower.box.centerX)
            if (cDelta / wMax > params.maxCenterDeltaRatio) return false
        }

        return true
    }
}
