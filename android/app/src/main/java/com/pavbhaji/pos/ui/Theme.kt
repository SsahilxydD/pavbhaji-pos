package com.pavbhaji.pos.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// One warm, high-contrast light theme for everyone — restaurants are bright
// places and cheap phones have washed-out screens, so we skip dark mode.
val Saffron = Color(0xFFF57F17)
val BhajiRed = Color(0xFFC63C0A)
val BhajiRedDark = Color(0xFF7A2E0E)
val Cream = Color(0xFFFFF8F2)
val CardCream = Color(0xFFFFFFFF)
val GoodGreen = Color(0xFF2E7D32)
val WarnAmber = Color(0xFFB26A00)
val InkDark = Color(0xFF2B2018)

private val scheme = lightColorScheme(
    primary = BhajiRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE0CC),
    onPrimaryContainer = BhajiRedDark,
    secondary = Saffron,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFECB3),
    onSecondaryContainer = Color(0xFF5D4000),
    tertiary = GoodGreen,
    background = Cream,
    onBackground = InkDark,
    surface = CardCream,
    onSurface = InkDark,
    surfaceVariant = Color(0xFFF4E7DC),
    onSurfaceVariant = Color(0xFF6B5D51),
    error = Color(0xFFB3261E),
)

@Composable
fun PBTheme(content: @Composable () -> Unit) {
    isSystemInDarkTheme() // intentionally ignored
    MaterialTheme(colorScheme = scheme, content = content)
}
