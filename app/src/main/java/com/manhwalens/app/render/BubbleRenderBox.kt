package com.manhwalens.app.render

/** What the debug/translation window needs to draw one bubble overlay. */
data class BubbleRenderBox(
    val id: Int,
    /** Box bounds in BITMAP pixels (OCR coordinate space). */
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val english: String,
    val arabic: String,
    /** Opaque ARGB sampled from inside the bubble. */
    val backgroundColor: Int,
    /** Contrasting text color for [backgroundColor]. */
    val textColor: Int
)
