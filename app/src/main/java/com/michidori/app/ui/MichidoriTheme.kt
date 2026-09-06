package com.michidori.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val MichidoriDarkColors = darkColorScheme(
    primary = Color(0xFF7DD3FC),
    onPrimary = Color(0xFF071018),
    secondary = Color(0xFF62D6A7),
    background = Color(0xFF071018),
    surface = Color(0xFF10202B),
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC),
)

@Composable
fun MichidoriTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MichidoriDarkColors,
        content = content,
    )
}
