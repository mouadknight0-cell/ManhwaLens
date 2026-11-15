package com.manhwalens.app.capture

import com.manhwalens.app.render.BubbleRenderBox
import com.manhwalens.app.translate.TranslatedBlock

/** Everything the debug overlay needs to render about one captured frame. */
data class CaptureMeta(
    val width: Int,
    val height: Int,
    val totalMs: Long,
    val firstFrameWaitMs: Long,
    val savedPath: String?,
    val nearBlack: Boolean,
    val capturedAt: Long,
    val ocrMs: Long = -1L,
    val ocrLineCount: Int = 0,
    val bubbleCount: Int = 0,
    val ocrError: String? = null,
    val translateEngine: String? = null,
    val translateMs: Long = -1L,
    val cacheHits: Int = 0,
    val cacheMisses: Int = 0,
    val translateError: String? = null,
    val translations: List<TranslatedBlock> = emptyList(),
    /** Pre-rendered boxes for the in-place Arabic overlay (bitmap px). */
    val bubbleBoxes: List<BubbleRenderBox> = emptyList(),
    /** Wall-clock from the lens tap to the moment the overlay window was posted. */
    val tapToOverlayMs: Long = -1L,
    /** Live mode: hide the footer, overlay is non-touchable, button owns interactions. */
    val liveMode: Boolean = false
)
