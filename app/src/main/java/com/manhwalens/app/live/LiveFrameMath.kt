package com.manhwalens.app.live

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Grayscale thumbnail math for live mode — pure Kotlin (JVM-testable):
 * luma conversion, frame-difference metric, stability tracking, and the
 * row-hash profile used to estimate the vertical scroll shift between frames.
 *
 * Thumbnails are THUMB_W × THUMB_H grayscale bytes (64×128). On a 720×1600
 * device one thumbnail spans the full frame, so 1 thumbnail row ≈ 12.5 px.
 */
object LiveFrameMath {

    const val THUMB_W = 64
    const val THUMB_H = 128

    /** Mean luma of one row — cheap row profile used by [RowHashShift]. */
    fun rowProfile(gray: ByteArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(h)
        for (y in 0 until h) {
            var sum = 0
            val base = y * w
            for (x in 0 until w) sum += gray[base + x].toInt() and 0xFF
            out[y] = sum / w.toFloat()
        }
        return out
    }

    /**
     * Mean per-pixel abs diff. Mismatched thumbnails report Float.MAX_VALUE (i.e. "completely
     * different") instead of throwing — a wrong-sized frame must read as CHANGING, never crash.
     */
    fun meanAbsoluteDifference(a: ByteArray, b: ByteArray): Float {
        if (a.size != b.size) return Float.MAX_VALUE // wrong-sized frame = "changed", never crash
        if (a.isEmpty()) return 0f
        var sum = 0L
        for (i in a.indices) {
            sum += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)).toLong()
        }
        return sum / a.size.toFloat()
    }

    /** true when two thumbnails differ by less than [tolerance] mean per pixel. */
    fun nearlyEqual(a: ByteArray, b: ByteArray, tolerance: Float = 2f): Boolean =
        meanAbsoluteDifference(a, b) < tolerance
}

/** Converts ARGB pixels to luma bytes (ITU-R BT.601). */
fun argbToLuma(pixels: IntArray): ByteArray {
    val out = ByteArray(pixels.size)
    for (i in pixels.indices) {
        val p = pixels[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        out[i] = ((r * 299 + g * 587 + b * 114) / 1000).toByte()
    }
    return out
}

/**
 * Stability tracker fed by the ~3 fps tick. Reports whether the frame is
 * CHANGING (scroll/animation) or STABLE — unchanged for at least
 * [stableWindowMs] after the last change.
 */
class LiveStabilityTracker(
    changeThreshold: Float = 10f,
    stableWindowMs: Long = 400L
) {
    sealed interface Verdict {
        /** Frame differs beyond threshold — content still moving. */
        data object Changing : Verdict
        /** Frame is still moving the tail of the stability window. */
        data object Settling : Verdict
        /** Stable for >= stableWindowMs. */
        data object Stable : Verdict
    }

    /** Elapsed ms since the last observed change (Long.MAX_VALUE before the first frame). */
    var lastChangeAtMs: Long = -1L
        private set

    /** Per-tick tunables (Part A sensitivity + stability slider). */
    var changeThreshold: Float = changeThreshold
    var stableWindowMs: Long = stableWindowMs

    /** Cells to exclude from the diff (own patches). null = none excluded. Set per cycle. */
    var excludeMask: BooleanArray? = null

    /** Mean abs diff of the most recent comparison (for the trace). */
    var lastDiff: Float = 0f
        private set

    private var previous: ByteArray? = null

    fun reset() {
        previous = null
        lastChangeAtMs = -1L
    }

    /** Snapshot of the last frame seen (pre-update when read BEFORE [onFrame]). */
    fun lastPrevious(): ByteArray? = previous?.copyOf()

    fun onFrame(gray: ByteArray, nowMs: Long): Verdict {
        val prev = previous
        previous = gray.copyOf()
        if (prev == null) {
            lastChangeAtMs = nowMs
            return Verdict.Changing
        }
        val mask = excludeMask
        val diff = DiffMask.meanAbsoluteDifferenceExcluding(prev, gray, mask)
        lastDiff = diff
        if (diff > changeThreshold) {
            lastChangeAtMs = nowMs
            return Verdict.Changing
        }
        val sinceChange = if (lastChangeAtMs < 0) Long.MAX_VALUE else nowMs - lastChangeAtMs
        return if (sinceChange >= stableWindowMs) Verdict.Stable else Verdict.Settling
    }
}

/**
 * Estimates the vertical shift between two frames using row-hash profiles —
 * i.e. for how many rows the new frame's rows match the old frame's rows
 * offset by `shift` (`curr[i] ≈ prev[i + shift]`).
 * Positive = user scrolled DOWN (content moved up; uncovered band at the bottom).
 * Negative = user scrolled back up; uncovered band at the top.
 */
object RowHashShift {

    data class Shift(
        /** Signed shift in thumbnail rows. */
        val rows: Int,
        /** Fraction of overlapping rows that match (0..1). */
        val confidence: Float
    )

    /**
     * @param eps max per-row mean-luma difference to count as a match
     * @param minConfidence required fraction of matching overlap rows
     * @param maxShiftRows search bound (default h/2)
     */
    fun estimate(
        prevGray: ByteArray,
        currGray: ByteArray,
        w: Int,
        h: Int,
        eps: Float = 14f,
        minConfidence: Float = 0.6f,
        maxShiftRows: Int = h / 2
    ): Shift? {
        // Defensive: wrong-sized input can never crash the live loop.
        if (prevGray.size != currGray.size || w <= 0 || h <= 0 || prevGray.size < w * h) return null
        // Direct row-vs-row SAD — far more discriminative than row means on
        // low-texture frames (means of random rows look identical; the rows themselves don't).
        var best: Shift? = null
        for (shift in -maxShiftRows..maxShiftRows) {
            val from = max(0, -shift)
            val to = min(h, h - shift)
            val overlap = to - from
            if (overlap < h * 0.35f) continue // need at least ~35% vertical overlap
            var matched = 0
            var i = from
            while (i < to) {
                val pBase = (i + shift) * w
                val cBase = i * w
                var rowSum = 0L
                var x = 0
                while (x < w) {
                    rowSum += abs((prevGray[pBase + x].toInt() and 0xFF) - (currGray[cBase + x].toInt() and 0xFF)).toLong()
                    x++
                }
                if (rowSum / w.toFloat() <= eps) matched++
                i++
            }
            val confidence = matched / overlap.toFloat()
            if (best == null || confidence > best.confidence) {
                best = Shift(shift, confidence)
            }
        }
        val winner = best ?: return null
        return if (winner.confidence >= minConfidence && winner.rows != 0) winner else null
    }
}
