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
val CardLine = Color(0xFFEADFD2)
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

// ---- Dish identity colours (the "cook by colour" system) ----
// Admin picks one per dish in Menu & prices; same dish = same colour on every
// screen. Items with no colour set fall back to a stable pick from the palette.
val DishPaletteHex = listOf("#E8483F", "#FFC93D", "#FF8A3D", "#6FCF6F", "#C79BFF", "#5BC8DB")
val DishPalette = listOf(
    Color(0xFFE8483F), Color(0xFFFFC93D), Color(0xFFFF8A3D),
    Color(0xFF6FCF6F), Color(0xFFC79BFF), Color(0xFF5BC8DB),
)

fun dishColour(hex: String, fallbackId: Long): Color {
    val h = hex.trim().removePrefix("#")
    if (h.length == 6) {
        h.toLongOrNull(16)?.let { return Color(0xFF000000L or it) }
    }
    val idx = (((fallbackId % DishPalette.size) + DishPalette.size) % DishPalette.size).toInt()
    return DishPalette[idx]
}

// ---- Kitchen dark palette: the chef screen is always-on in a bright kitchen,
// dark ground makes the dish colours carry across the room. ----
object Kc {
    val bg = Color(0xFF16110C)
    val card = Color(0xFF211A12)
    val inside = Color(0xFF1B1510)
    val border = Color(0xFF322A1F)
    val ink = Color(0xFFF4EDE4)
    val ink2 = Color(0xFFA89A88)
    val note = Color(0xFFFFB59E)
    val fullBg = Color(0xFF3D2C00)
    val fullFg = Color(0xFFEFB02E)
    val lockBg = Color(0xFF2C313A)
    val lockFg = Color(0xFFC7CDD6)
    val mineBg = Color(0xFF4A2018)
    val mineFg = Color(0xFFFFB59E)
    val take = Color(0xFFF2762E)
    val takeFg = Color(0xFF2B1000)
    val ready = Color(0xFF34B369)
    val readyFg = Color(0xFF04220F)
    val warn = Color(0xFFEFB02E)
    val warnFg = Color(0xFF3D2C00)
    val late = Color(0xFFE14B3C)
    val live = Color(0xFF8FBF9A)
}
