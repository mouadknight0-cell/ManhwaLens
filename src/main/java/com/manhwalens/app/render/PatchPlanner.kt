package com.manhwalens.app.render

import com.manhwalens.app.ocr.geometry.Rect2D

/**
 * Paint-area planning for overlapping bubbles (v0.11.0).
 *
 * The overlay paints blocks one at a time as their Arabic lands, so a bubble that OCR
 * split into two blocks used to get TWO patches whose edges overlapped: the second patch's
 * ring repainted half of the first patch's Arabic (the "smeared" text in the report) and
 * the English near the seam was left uncovered ("English still visible").
 *
 * The fix: decide the areas ONCE, from the whole page, before anything is painted.
 * Every area has one stable owner ID and one opaque composite patch. Translated text from
 * all available members is combined in page order; sibling patches never overwrite one
 * another. [areas] is computed from all boxes, so the cover never depends on arrival order.
 *
 * Pure Kotlin (JVM-tested).
 */
object PatchPlanner {

    /** One composite patch area: [ownerId] is its stable ID, [memberIds] contribute text, [cover] bounds it. */
    data class Area(
        val ownerId: Int,
        val memberIds: List<Int>,
        val cover: Rect2D
    ) {
        val merged: Boolean get() = memberIds.size > 1
    }

    /** Areas for the whole page, in [boxes] order (which is priority order). */
    fun areas(
        boxes: List<Pair<Int, Rect2D>>,
        frameW: Int,
        frameH: Int,
        dilatePx: Float = PatchCoverage.DEFAULT_DILATE_PX,
        mergeGapPx: Float = PatchCoverage.DEFAULT_MERGE_GAP_PX
    ): List<Area> = PatchCoverage.plan(boxes, frameW, frameH, dilatePx, mergeGapPx)
        .map { Area(it.ownerId, it.memberIds, it.rect) }

    /** Block that draws the Arabic of [id]'s area (itself for an unmerged block). */
    fun ownerOf(areas: List<Area>, id: Int): Int =
        areas.firstOrNull { id in it.memberIds }?.ownerId ?: id

    /** Opaque rect that erases [id]'s area (null when [id] is not in any area). */
    fun coverFor(areas: List<Area>, id: Int): Rect2D? =
        areas.firstOrNull { it.ownerId == id }?.cover ?: areas.firstOrNull { id in it.memberIds }?.cover

    /** (ownerId, cover) pairs — one per rendered patch. */
    fun renders(areas: List<Area>): List<Pair<Int, Rect2D>> = areas.map { it.ownerId to it.cover }

    /**
     * Build one opaque patch payload per visual area. All translated member blocks are
     * combined in page order so a later opaque patch can never erase an earlier sibling's
     * Arabic. Missing translations stay absent, while the area's full cover is retained.
     */
    fun compose(
        areas: List<Area>,
        boxes: List<BubbleRenderBox>
    ): List<BubbleRenderBox> {
        val byId = boxes.associateBy { it.id }
        return areas.mapNotNull { area ->
            val members = area.memberIds.mapNotNull { byId[it] }
                .filter { it.arabic.isNotBlank() }
            if (members.isEmpty()) return@mapNotNull null
            val owner = byId[area.ownerId] ?: members.first()
            owner.copy(
                id = area.ownerId,
                left = area.cover.left.toInt(),
                top = area.cover.top.toInt(),
                right = area.cover.right.toInt(),
                bottom = area.cover.bottom.toInt(),
                english = members.map { it.english }.filter { it.isNotBlank() }.joinToString(" "),
                arabic = members.map { it.arabic.trim() }.joinToString(" ")
            )
        }
    }

    /** How many boxes were folded into another block's patch (for the pipeline trace). */
    fun foldedCount(areas: List<Area>): Int = areas.sumOf { it.memberIds.size - 1 }

    /** One-line summary for the pipeline trace. */
    fun describe(boxesKnown: Int, areas: List<Area>): String =
        "$boxesKnown boxes → ${areas.size} patch area(s)" +
            (if (foldedCount(areas) > 0) " (${foldedCount(areas)} merged)" else "")
}
