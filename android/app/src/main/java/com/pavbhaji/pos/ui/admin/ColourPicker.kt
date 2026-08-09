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

// One dialog for every colour decision in the app: tap a swatch, or mix any
// colour with the square + hue bar. Returns #RRGGBB via onPick.
@Composable
fun ColourPickerDialog(
    title: String,
    initial: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
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
