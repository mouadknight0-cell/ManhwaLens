package com.manhwalens.app.render

import android.content.Context
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import com.manhwalens.app.R
import kotlin.math.ceil
import kotlin.math.max

/**
 * Production measurer backed by StaticLayout + the bundled Cairo Bold font —
 * the exact same stack Compose/Android uses to draw the text, so the computed
 * size really predicts the rendered height.
 */
class StaticTextMeasurer(context: Context) : TextMeasurer {

    private val typeface =
        ResourcesCompat.getFont(context, R.font.cairo_bold)
            ?: ResourcesCompat.getFont(context, R.font.cairo)
            ?: android.graphics.Typeface.DEFAULT

    override fun measure(text: String, maxWidthPx: Int, fontSizePx: Float): Measured {
        if (text.isBlank() || maxWidthPx <= 0) return Measured(0, 0)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = this@StaticTextMeasurer.typeface
            this.textSize = fontSizePx
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, maxWidthPx)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.15f)
            .build()
        var widest = 0f
        for (i in 0 until layout.lineCount) {
            widest = max(widest, layout.getLineWidth(i))
        }
        return Measured(layout.height, ceil(widest).toInt())
    }
}
