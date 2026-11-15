package com.manhwalens.app.render

import com.manhwalens.app.ocr.geometry.Rect2D

/**
 * Opaque-patch planning (v0.11.0) — fixes the "English still visible behind / around the
 * Arabic" and the "smeared overlapping Arabic at patch edges" defects.
 *
 * Root causes on real pages:
 *  1. a patch only covered its own OCR box + a few px pad, so glyph pixels that ML Kit
 *     clipped (ascender/descender tails, a wider second line, a stroke shadow) stayed
 *     under the overlay and peeked out around the Arabic;
 *  2. two blocks of the SAME bubble (OCR split one bubble into two groups) produced two
 *     patches whose feathers overlapped, so the second patch's semi-transparent edge
 *     repainted half of the first patch's Arabic — the "smeared" look;
 *  3. patch edges were feathered to TRANSPARENT, which lets the screen — i.e. the original
 *     English — show straight through the blended ring.
 *
 * [plan] therefore groups blocks that belong to one visual area, dilates every rect so no
 * glyph can survive outside it, and returns ONE cover rect per group (the union). The
 * caller paints that rect fully OPAQUE once, and lets the group's owner block draw the
 * Arabic, so overlapping Arabic can never be smeared.
 *
 * Pure Kotlin (JVM-tested): no android.* references.
 */
object PatchCoverage {

    /** Default outward growth of a cover rect, in pixels. Covers glyph tails and shadows. */
    const val DEFAULT_DILATE_PX = 3f

    /**
     * Rects closer than this to each other (edge to edge, both axes) are treated as one
     * visual area — two OCR lines of one bubble sit a couple of px apart, while two real
     * bubbles on a manhwa page are never this close.
     */
    const val DEFAULT_MERGE_GAP_PX = 4f

    /** One painted area: the union rect plus every block that contributed to it. */
    data class Group(
        /** Block that draws the Arabic (the first/lowest id — priority order wins). */
        val ownerId: Int,
        /** Every block id covered by [rect]; the others draw nothing. */
        val memberIds: List<Int>,
        val rect: Rect2D
    )

    /**
     * Group + dilate [boxes] into disjoint cover rects, clamped to the frame.
     *
     * @param boxes   (block id, OCR bounds in bitmap pixels)
     * @param frameW  frame width  (≤ 0 disables clamping)
     * @param frameH  frame height (≤ 0 disables clamping)
     */
    fun plan(
        boxes: List<Pair<Int, Rect2D>>,
        frameW: Int = 0,
        frameH: Int = 0,
        dilatePx: Float = DEFAULT_DILATE_PX,
        mergeGapPx: Float = DEFAULT_MERGE_GAP_PX
    ): List<Group> {
        if (boxes.isEmpty()) return emptyList()
        val grow = dilatePx.coerceAtLeast(0f)
        val gap = mergeGapPx.coerceAtLeast(0f)

        // 1) dilate + clamp
        val grown = boxes.map { (id, r) ->
            id to clamp(
                Rect2D(r.left - grow, r.top - grow, r.right + grow, r.bottom + grow),
                frameW, frameH
            )
        }

        // 2) union-find over "same visual area"
        val n = grown.size
        val parent = IntArray(n) { it }
        fun find(i: Int): Int {
            var r = i
            while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }
            return r
        }
        fun union(a: Int, b: Int) {
            val ra = find(a); val rb = find(b)
            if (ra != rb) parent[max2(ra, rb)] = min2(ra, rb)
        }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (near(grown[i].second, grown[j].second, gap)) union(i, j)
            }
        }

        // 3) one group per root, in first-seen order (block order = priority order)
        val order = ArrayList<Int>()
        val members = HashMap<Int, MutableList<Int>>()
        for (i in 0 until n) {
            val root = find(i)
            if (members[root] == null) { members[root] = ArrayList(); order += root }
            members.getValue(root) += i
        }
        return order.map { root ->
            val idx = members.getValue(root)
            var rect = grown[idx.first()].second
            for (k in idx.drop(1)) rect = unionRect(rect, grown[k].second)
            Group(
                ownerId = grown[idx.first()].first,
                memberIds = idx.map { grown[it].first },
                rect = rect
            )
        }
    }

    /** Cover rect of the group that owns [id] (null when the block is not covered). */
    fun coverOf(groups: List<Group>, id: Int): Rect2D? =
        groups.firstOrNull { id in it.memberIds }?.rect

    /** Owners of every group that has more than one member (for the pipeline trace). */
    fun mergedGroups(groups: List<Group>): List<Group> = groups.filter { it.memberIds.size > 1 }

    // ------------------------------------------------------------------ helpers

    private fun near(a: Rect2D, b: Rect2D, gap: Float): Boolean {
        val dx = max2(0f, max2(a.left - b.right, b.left - a.right))
        val dy = max2(0f, max2(a.top - b.bottom, b.top - a.bottom))
        return dx <= gap && dy <= gap
    }

    private fun unionRect(a: Rect2D, b: Rect2D) = Rect2D(
        min2(a.left, b.left), min2(a.top, b.top), max2(a.right, b.right), max2(a.bottom, b.bottom)
    )

    private fun clamp(r: Rect2D, frameW: Int, frameH: Int): Rect2D {
        val maxX = if (frameW > 0) frameW.toFloat() else Float.MAX_VALUE
        val maxY = if (frameH > 0) frameH.toFloat() else Float.MAX_VALUE
        val l = r.left.coerceIn(0f, maxX)
        val t = r.top.coerceIn(0f, maxY)
        val rr = r.right.coerceIn(l, maxX)
        val bb = r.bottom.coerceIn(t, maxY)
        return Rect2D(l, t, rr, bb)
    }

    private fun min2(a: Float, b: Float) = if (a < b) a else b
    private fun max2(a: Float, b: Float) = if (a > b) a else b
    private fun min2(a: Int, b: Int) = if (a < b) a else b
    private fun max2(a: Int, b: Int) = if (a > b) a else b
}
