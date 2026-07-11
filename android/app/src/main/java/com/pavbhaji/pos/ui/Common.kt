package com.pavbhaji.pos.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun rupees(paise: Long): String =
    if (paise % 100 == 0L) "₹${paise / 100}"
    else "₹${paise / 100}.${(paise % 100).toString().padStart(2, '0')}"

fun items(n: Long): String = if (n == 1L) "1 item" else "$n items"

fun ageMinutes(sinceMs: Long): String {
    val m = ((System.currentTimeMillis() - sinceMs) / 60000).coerceAtLeast(0)
    return when {
        m < 1 -> "now"
        m < 60 -> "${m}m"
        else -> "${m / 60}h${m % 60}m"
    }
}

fun clockTime(ms: Long): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = ms
    return "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
}

// The one header used by every screen. Gives the app its identity (deep red bar),
// a 48dp back button that's easy to hit, and — crucially — wires the system
// back gesture/button to the same onBack, so "swipe back" always works.
@Composable
fun AppHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    if (onBack != null) BackHandler(onBack = onBack)
    Surface(color = BhajiRed) {
        // safeDrawing (status bar ∪ display cutout) instead of just the status
        // bar: on notch/full-screen phones the cutout can be taller than the
        // reported status bar, and the bar's content must start below BOTH.
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                )
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            } else {
                Text("🍛", fontSize = 21.sp, modifier = Modifier.padding(start = 12.dp, end = 4.dp))
            }
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Text(
                    title, color = Color.White, fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        subtitle, color = Color(0xFFFFD9C4), fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            actions()
        }
    }
}

@Composable
fun HeaderTextAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

@Composable
fun ConnBanner(connected: Boolean) {
    if (!connected) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFB3261E))
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "reconnecting to server… check WiFi",
                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun StatusChip(status: String) {
    val (bg, fg) = when (status) {
        "pending" -> Color(0xFFFFECB3) to Color(0xFF5D4000)
        "preparing" -> Color(0xFFFFD180) to Color(0xFF7A3E00)
        "ready" -> Color(0xFFC8E6C9) to Color(0xFF1B5E20)
        "served" -> Color(0xFFE0E0E0) to Color(0xFF424242)
        "cancelled" -> Color(0xFFFFCDD2) to Color(0xFF8E0000)
        else -> Color.LightGray to Color.DarkGray
    }
    Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
        Text(
            status, color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// 4-digit PIN pad. Submits by itself on the 4th digit — no OK button.
@Composable
fun PinPad(onSubmit: (String) -> Unit, error: String?) {
    var pin by remember { mutableStateOf("") }
    androidx.compose.runtime.LaunchedEffect(error) { if (error != null) pin = "" }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(4) { i ->
                Box(
                    Modifier
                        .size(18.dp)
                        .background(
                            if (i < pin.length) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        )
                )
            }
        }
        Text(
            error ?: " ",
            color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
            modifier = Modifier.padding(top = 10.dp)
        )
        Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫"))
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { key ->
                        if (key.isEmpty()) {
                            Box(Modifier.size(width = 88.dp, height = 62.dp))
                        } else {
                            Button(
                                onClick = {
                                    if (key == "⌫") {
                                        if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                    } else if (pin.length < 4) {
                                        pin += key
                                        if (pin.length == 4) {
                                            val p = pin
                                            onSubmit(p)
                                        }
                                    }
                                },
                                modifier = Modifier.size(width = 88.dp, height = 62.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurface
                                ),
                                elevation = ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                            ) {
                                Text(key, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReasonDialog(title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("A reason is required — it goes in the audit log.", fontSize = 13.sp)
                OutlinedTextField(
                    value = reason, onValueChange = { reason = it },
                    label = { Text("Reason") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (reason.trim().length >= 2) onConfirm(reason.trim()) },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text("Cancel item") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back") } }
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, fontSize = 15.sp) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back") } }
    )
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier, accent: Color = BhajiRed) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = accent, textAlign = TextAlign.Center)
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun QtyStepper(qty: Long, onChange: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = { onChange(qty - 1) },
            modifier = Modifier.size(42.dp),
            shape = CircleShape,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
        ) { Text("−", fontSize = 20.sp) }
        Text(
            qty.toString(),
            fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.width(34.dp),
            textAlign = TextAlign.Center
        )
        OutlinedButton(
            onClick = { onChange(qty + 1) },
            modifier = Modifier.size(42.dp),
            shape = CircleShape,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
        ) { Text("+", fontSize = 20.sp) }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
    )
}

@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().height(90.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}
