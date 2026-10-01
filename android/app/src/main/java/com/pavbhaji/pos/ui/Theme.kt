package com.pavbhaji.pos.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.pavbhaji.pos.R

// One warm, high-contrast light theme for everyone — restaurants are bright
// places and cheap phones have washed-out screens, so we skip dark mode.
// Every colour that carries TEXT clears WCAG AA (4.5:1) on the grounds it is
// used on — white, cream, its own pill — so small grey and amber words stay
// readable under a tube light or in the sun at the door.
val Saffron = Color(0xFFF57F17)
val BhajiRed = Color(0xFFC63C0A)
val BhajiRedDark = Color(0xFF7A2E0E)
val Cream = Color(0xFFFAF6F0)
val CardCream = Color(0xFFFFFFFF)
val CardLine = Color(0xFFEFE6DA)
val GoodGreen = Color(0xFF187A46)          // 5.4:1 on white, white on it 5.4:1
val WarnAmber = Color(0xFF8F5500)          // 4.8:1 on its #F5E3C2 pill, 6:1 under white
val InkDark = Color(0xFF221A12)

// Extra semantic tones shared by the light screens, so nobody hand-mixes hex.
object Pb {
    val headerTop = Color(0xFFC23E0C)      // AppHeader gradient, top-left
    val headerBottom = Color(0xFFA93307)   // AppHeader gradient, bottom-right
    val headerSub = Color(0xFFFFF0E8)      // ≥ 4.7:1 anywhere on the header
    val ink2 = Color(0xFF6E6055)           // secondary text on cream
    val ink3 = Color(0xFF76675B)           // tertiary / hints — 5:1 on cream
    val successBg = Color(0xFFE4F6EA)
    val successLine = Color(0xFF59B87E)
    val successDeep = Color(0xFF187A46)
    val warnBg = Color(0xFFFFF3E2)
    val warnLine = Color(0xFFF2C88F)
    val infoBg = Color(0xFFE9F0FB)
    val infoLine = Color(0xFF8FB2E4)
    val infoDeep = Color(0xFF2F6FCC)
    val danger = Color(0xFFC0392B)         // 4.8:1 on dangerBg, 5:1 on cream
    val dangerBg = Color(0xFFFDECEA)
    val counterBg = Color(0xFFFFFBF2)      // "just carry" counter tint
    val counterSpine = Color(0xFFF2A33C)
    val quietCard = Color(0xFFFBF7F1)      // free-table card
    val skeleton = Color(0xFFF0E8DE)       // shimmer base on cream
}

// Inter for Latin text and numerals (the modern-app feel); Hindi / Gujarati
// glyphs automatically fall back to the system Noto fonts, which pair fine.
val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

private fun TextStyle.inter() = copy(fontFamily = InterFamily)

private val baseType = Typography()
private val PbTypography = Typography(
    displayLarge = baseType.displayLarge.inter(),
    displayMedium = baseType.displayMedium.inter(),
    displaySmall = baseType.displaySmall.inter(),
    headlineLarge = baseType.headlineLarge.inter(),
    headlineMedium = baseType.headlineMedium.inter(),
    headlineSmall = baseType.headlineSmall.inter(),
    titleLarge = baseType.titleLarge.inter().copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
    titleMedium = baseType.titleMedium.inter().copy(fontWeight = FontWeight.SemiBold),
    titleSmall = baseType.titleSmall.inter(),
    bodyLarge = baseType.bodyLarge.inter(),
    bodyMedium = baseType.bodyMedium.inter(),
    bodySmall = baseType.bodySmall.inter(),
    labelLarge = baseType.labelLarge.inter().copy(fontWeight = FontWeight.SemiBold),
    labelMedium = baseType.labelMedium.inter(),
    labelSmall = baseType.labelSmall.inter(),
)

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
    surfaceVariant = Color(0xFFF4E9DE),
    onSurfaceVariant = Pb.ink2,
    outline = CardLine,
    outlineVariant = CardLine,
    error = Pb.danger,
    // Dialogs, dropdowns and sheets paint themselves with the surfaceContainer
    // tokens; left unset they fell back to Material's stock lavender. White
    // and warm creams instead, and no purple tint on raised surfaces.
    surfaceTint = Color.Transparent,
    surfaceBright = CardCream,
    surfaceDim = Color(0xFFEFE7DD),
    surfaceContainerLowest = CardCream,
    surfaceContainerLow = Color(0xFFFFFCF8),
    surfaceContainer = CardCream,
    surfaceContainerHigh = CardCream,
    surfaceContainerHighest = Color(0xFFF7EFE6),
)

@Composable
fun PBTheme(content: @Composable () -> Unit) {
    isSystemInDarkTheme() // intentionally ignored
    MaterialTheme(colorScheme = scheme, typography = PbTypography, content = content)
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
