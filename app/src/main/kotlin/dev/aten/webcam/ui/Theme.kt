package dev.aten.webcam.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Always dark: on the OLED panels of most spare phones a black screen draws almost no power.
private val Colors = darkColorScheme(
    primary = Color(0xFF4DD0A8),
    onPrimary = Color(0xFF06231A),
    background = Color.Black,
    surface = Color(0xFF0B0E11),
    surfaceVariant = Color(0xFF151A20),
    error = Color(0xFFFF6B6B),
)

@Composable
fun WebcamTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
