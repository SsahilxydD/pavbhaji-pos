package com.pavbhaji.pos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun rupees(paise: Long): String =
    if (paise % 100 == 0L) "₹${paise / 100}"
    else "₹${paise / 100}.${(paise % 100).toString().padStart(2, '0')}"

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
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val min = cal.get(java.util.Calendar.MINUTE)
    return "%02d:%02d".format(h, min)
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
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
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
            status,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// Numeric PIN pad used by the login screen.
@Composable
fun PinPad(onSubmit: (String) -> Unit, error: String?) {
    var pin by remember { mutableStateOf("") }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            repeat(6) { i ->
                Box(
                    Modifier
                        .size(16.dp)
                        .background(
                            if (i < pin.length) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        )
                )
            }
        }
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp))
        }
        Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"))
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { key ->
                        val isOk = key == "OK"
                        Button(
                            onClick = {
                                when (key) {
                                    "⌫" -> if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                    "OK" -> if (pin.length >= 4) { onSubmit(pin); pin = "" }
                                    else -> if (pin.length < 6) pin += key
                                }
                            },
                            modifier = Modifier.size(width = 84.dp, height = 60.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = if (isOk)
                                androidx.compose.material3.ButtonDefaults.buttonColors()
                            else androidx.compose.material3.ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            )
                        ) {
                            Text(key, fontSize = if (isOk) 17.sp else 22.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// Dialog that demands a reason — used for every cancellation.
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = { if (reason.trim().length >= 2) onConfirm(reason.trim()) }) {
                Text("Cancel item")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back") } }
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
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
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = { onChange(qty - 1) },
            modifier = Modifier.size(38.dp),
            shape = CircleShape,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
        ) { Text("−", fontSize = 18.sp) }
        Text(
            qty.toString(),
            fontSize = 17.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.width(28.dp),
            textAlign = TextAlign.Center
        )
        OutlinedButton(
            onClick = { onChange(qty + 1) },
            modifier = Modifier.size(38.dp),
            shape = CircleShape,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
        ) { Text("+", fontSize = 18.sp) }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp)
    )
}

@Composable
fun EmptyHint(text: String) {
    Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
    }
}
