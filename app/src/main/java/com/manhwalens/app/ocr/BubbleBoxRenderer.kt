package com.manhwalens.app.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface

/**
 * Annotates a captured frame with grouping diagnostics:
 *  - violet thin box  = raw OCR line box (with tiny line index)
 *  - amber thick box  = merged bubble block (with bubble number)
 *  - lines under very low confidence (< 0.3) are tinted red instead of violet
 */
object BubbleBoxRenderer {

    fun drawAnnotations(bitmap: Bitmap, blocks: List<BubbleBlock>) {
        val canvas = Canvas(bitmap)
        val density = bitmap.width / 400f // scale stroke widths with resolution
        fun sw(v: Float) = (v * density).coerceAtLeast(2f)

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = sw(1.2f)
            color = Color.rgb(139, 124, 246) // LensViolet
            alpha = 200
        }
        val lowConfPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = sw(1.2f)
            color = Color.rgb(255, 90, 90)
            alpha = 220
        }
        val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = sw(3.2f)
            color = Color.rgb(255, 196, 94) // PanelAmber
            alpha = 235
        }
        val bubbleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(255, 196, 94)
            alpha = 26
        }
        val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 16, 36)
            textSize = sw(11f)
            typeface = Typeface.DEFAULT_BOLD
        }
        val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.rgb(255, 196, 94)
        }

        for (block in blocks) {
            // Bubble (merged) box
            val b = block.bounds
            val rect = RectF(b.left, b.top, b.right, b.bottom)
            canvas.drawRect(rect, bubbleFill)
            canvas.drawRect(rect, bubblePaint)

            // Bubble number chip, top-left of the bubble box
            val chipText = block.id.toString()
            val tw = labelText.measureText(chipText)
            val chipPadX = sw(4f)
            val chipH = sw(13f)
            val chipTop = (b.top - chipH).coerceAtLeast(0f)
            canvas.drawRect(RectF(b.left, chipTop, b.left + tw + chipPadX * 2, chipTop + chipH), labelBg)
            canvas.drawText(chipText, b.left + chipPadX, chipTop + chipH - sw(3.5f), labelText)

            // Raw line boxes
            for (lineBox in block.lines) {
                val l = lineBox.box
                canvas.drawRect(
                    RectF(l.left, l.top, l.right, l.bottom),
                    if (lineBox.confidence > 0f && lineBox.confidence < 0.3f) lowConfPaint else linePaint
                )
            }
        }
    }
}
