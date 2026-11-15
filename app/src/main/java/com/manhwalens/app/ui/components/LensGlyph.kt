package com.manhwalens.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.manhwalens.app.ui.theme.LensViolet
import com.manhwalens.app.ui.theme.PanelAmber

/**
 * Brand mark: eye/lens drawn on Canvas. Kept here so all screens share it.
 */
@Composable
fun LensGlyph(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val c = Offset(size.width / 2f, size.height / 2f)
        val r = size.minDimension / 2f

        drawCircle(
            color = LensViolet.copy(alpha = 0.18f),
            radius = r,
            center = c
        )
        drawCircle(
            color = LensViolet,
            radius = r * 0.68f,
            center = c,
            style = Stroke(width = size.minDimension * 0.075f)
        )
        drawCircle(
            color = PanelAmber,
            radius = r * 0.24f,
            center = c
        )
        drawCircle(
            color = Color.White.copy(alpha = 0.85f),
            radius = r * 0.07f,
            center = Offset(c.x - r * 0.16f, c.y - r * 0.16f)
        )
    }
}
