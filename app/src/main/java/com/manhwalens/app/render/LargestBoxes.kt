package com.manhwalens.app.render

/** Pure helper: pick the [n] largest boxes by pixel area (long arithmetic; ties keep order). */
object LargestBoxes {

    fun areaOf(box: BubbleRenderBox): Long =
        (box.right - box.left).coerceAtLeast(0).toLong() * (box.bottom - box.top).coerceAtLeast(0).toLong()

    fun select(boxes: List<BubbleRenderBox>, n: Int): List<BubbleRenderBox> =
        boxes.sortedByDescending { areaOf(it) }.take(n.coerceAtLeast(0))
}
