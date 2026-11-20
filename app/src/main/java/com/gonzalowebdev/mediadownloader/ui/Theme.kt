package com.gonzalowebdev.mediadownloader.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8B7CFF),
    onPrimary = Color(0xFF12082F),
    secondary = Color(0xFF3DDCC8),
    background = Color(0xFF0B0B10),
    onBackground = Color(0xFFECECF3),
    surface = Color(0xFF14141B),
    onSurface = Color(0xFFECECF3),
    surfaceVariant = Color(0xFF1E1E28),
    onSurfaceVariant = Color(0xFFA6A6B8),
    error = Color(0xFFFF6B6B),
    outline = Color(0xFF3A3A4A)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DarkScheme, content = content)
}
