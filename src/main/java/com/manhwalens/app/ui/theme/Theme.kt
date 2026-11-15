package com.manhwalens.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ManhwaLensDarkScheme = darkColorScheme(
    primary = LensViolet,
    onPrimary = InkBlack,
    primaryContainer = InkSurfaceHigh,
    onPrimaryContainer = LensVioletSoft,
    secondary = PanelAmber,
    onSecondary = InkBlack,
    background = InkBlack,
    onBackground = TextPrimary,
    surface = InkSurface,
    onSurface = TextPrimary,
    surfaceVariant = InkSurfaceHigh,
    onSurfaceVariant = TextSecondary
)

@Composable
fun ManhwaLensTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ManhwaLensDarkScheme,
        typography = ManhwaLensTypography,
        content = content
    )
}
