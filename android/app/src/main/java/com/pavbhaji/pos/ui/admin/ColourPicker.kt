package com.pavbhaji.pos.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pavbhaji.pos.ui.CardLine
import com.pavbhaji.pos.ui.InkDark
import com.pavbhaji.pos.ui.dishColour

// 18 ready-made swatches, all bright enough to carry across the room on the
// dark kitchen board. The first six are the classic seed palette.
val PickerPalette = listOf(
    "#E8483F", "#FF8A3D", "#F2A33C", "#FFC93D", "#A8C93D", "#6FCF6F",
    "#2E9E6B", "#4FB8A8", "#5BC8DB", "#4D9DE0", "#5A78F0", "#7E6BD9",
    "#C79BFF", "#D93A7C", "#FF6FA5", "#B0836A", "#94A3B8", "#55606E",
)

private fun hexToHsv(hex: String): FloatArray {
    val v = hex.trim().removePrefix("#").toLongOrNull(16) ?: 0xE8483F
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV((0xFF000000L or v).toInt(), hsv)
    return hsv
}

private fun hsvToHex(h: Float, s: Float, v: Float): String {
    val c = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    return "#%06X".format(c and 0xFFFFFF)
}

// sRGB hex → CIE Lab (D65), for "how different do these two look" (ΔE76).
private fun lab(hex: String): DoubleArray? {
    val v = hex.trim().removePrefix("#").toLongOrNull(16) ?: return null
    fun lin(c: Long): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4)
    }
    val r = lin((v shr 16) and 0xFF); val g = lin((v shr 8) and 0xFF); val b = lin(v and 0xFF)
    fun f(t: Double) = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116
    val fx = f((0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047)
    val fy = f(0.2126 * r + 0.7152 * g + 0.0722 * b)
    val fz = f((0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883)
    return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
}

private fun deltaE(a: String, b: String): Double? {
    val x = lab(a) ?: return null
    val y = lab(b) ?: return null
    return Math.sqrt((x[0] - y[0]).let { it * it } + (x[1] - y[1]).let { it * it } + (x[2] - y[2]).let { it * it })
}

// Chefs read bucket colours from across the kitchen, and Pav Bhaji orange vs
// Pulav orange (ΔE ≈ 19) merge on a dimmed bucket. Under a pick this says
// when it is the SAME colour as another kitchen dish (they share one bucket —
// that's how dishes are clubbed, so it's said, not warned), or only CLOSE to
// one (ΔE < 25), which is the mix-up. `others` = (dish name, #RRGGBB) of the
// other kitchen dishes. Nothing is blocked; the owner decides.
@Composable
fun ColourClashNote(hex: String, others: List<Pair<String, String>>) {
    val same = others.filter { it.second.equals(hex, ignoreCase = true) }.map { it.first }.distinct()
    val close = others.filter { !it.second.equals(hex, ignoreCase = true) && (deltaE(hex, it.second) ?: 99.0) < 25 }
        .map { it.first }.distinct()
    fun names(ns: List<String>) = ns.take(2).joinToString(", ") + if (ns.size > 2) " +${ns.size - 2}" else ""
    if (close.isNotEmpty()) {
        Text(
            com.pavbhaji.pos.ui.S.colourTooClose(names(close)),
            fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
            color = com.pavbhaji.pos.ui.WarnAmber,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
    if (same.isNotEmpty()) {
        Text(
            com.pavbhaji.pos.ui.S.colourSameAs(names(same)),
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

// One dialog for every colour decision in the app: tap a swatch, or mix any
// colour with the square + hue bar. Returns #RRGGBB via onPick.
@Composable
fun ColourPickerDialog(
    title: String,
    initial: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    others: List<Pair<String, String>> = emptyList()
) {
    val start = remember(initial) { hexToHsv(initial.ifBlank { PickerPalette[0] }) }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var vl by remember { mutableFloatStateOf(start[2]) }
    val hex = hsvToHex(hue, sat, vl)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                PickerPalette.chunked(6).forEach { row ->
                    Row(
                        Modifier.padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        row.forEach { p ->
                            val sel = hex.equals(p, ignoreCase = true)
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(dishColour(p, 0))
                                    .border(
                                        if (sel) 3.dp else 0.dp,
                                        if (sel) InkDark else Color.Transparent,
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        val h = hexToHsv(p)
                                        hue = h[0]; sat = h[1]; vl = h[2]
                                    }
                            )
                        }
                    }
                }
                Text(
                    com.pavbhaji.pos.ui.S.orMixOwn,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
                SvSquare(hue, sat, vl) { s, v -> sat = s; vl = v }
                HueBar(hue) { hue = it }
                Row(
                    Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.hsv(hue.coerceIn(0f, 360f), sat, vl))
                            .border(1.dp, CardLine, RoundedCornerShape(10.dp))
                    )
                    Text(
                        hex,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
                ColourClashNote(hex, others)
            }
        },
        confirmButton = { Button(onClick = { onPick(hex) }) { Text(com.pavbhaji.pos.ui.S.save) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(com.pavbhaji.pos.ui.S.back) } }
    )
}

// Saturation (→) × brightness (↓) square for the current hue.
@Composable
private fun SvSquare(hue: Float, sat: Float, vl: Float, onChange: (Float, Float) -> Unit) {
    var size by remember { mutableStateOf(IntSize(1, 1)) }
    val hueColour = Color.hsv(hue.coerceIn(0f, 360f), 1f, 1f)
    fun update(x: Float, y: Float) = onChange(
        (x / size.width).coerceIn(0f, 1f),
        1f - (y / size.height).coerceIn(0f, 1f)
    )
    Box(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .onSizeChanged { size = it }
            .clip(RoundedCornerShape(12.dp))
            .background(Brush.horizontalGradient(listOf(Color.White, hueColour)))
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            .pointerInput(Unit) { detectTapGestures { p -> update(p.x, p.y) } }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    update(change.position.x, change.position.y)
                }
            }
    ) {
        Box(
            Modifier
                .offset {
                    IntOffset(
                        (sat * size.width).toInt() - 11.dp.roundToPx(),
                        ((1f - vl) * size.height).toInt() - 11.dp.roundToPx()
                    )
                }
                .size(22.dp)
                .border(3.dp, Color.White, CircleShape)
                .border(4.dp, Color(0x33000000), CircleShape)
        )
    }
}

@Composable
private fun HueBar(hue: Float, onChange: (Float) -> Unit) {
    var size by remember { mutableStateOf(IntSize(1, 1)) }
    val rainbow = remember {
        listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color.hsv(it % 360f, 1f, 1f) }
    }
    fun update(x: Float) = onChange((x / size.width).coerceIn(0f, 1f) * 359.9f)
    Box(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .height(26.dp)
            .onSizeChanged { size = it }
            .clip(RoundedCornerShape(13.dp))
            .background(Brush.horizontalGradient(rainbow))
            .pointerInput(Unit) { detectTapGestures { p -> update(p.x) } }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    change.consume()
                    update(change.position.x)
                }
            }
    ) {
        Box(
            Modifier
                .offset { IntOffset(((hue / 360f) * size.width).toInt() - 11.dp.roundToPx(), 2.dp.roundToPx()) }
                .size(22.dp)
                .border(3.dp, Color.White, CircleShape)
                .border(4.dp, Color(0x33000000), CircleShape)
        )
    }
}
