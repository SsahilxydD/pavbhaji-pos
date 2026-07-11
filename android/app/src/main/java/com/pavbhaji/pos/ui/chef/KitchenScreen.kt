package com.pavbhaji.pos.ui.chef

import android.app.Activity
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pavbhaji.pos.Deps
import com.pavbhaji.pos.data.ApiResult
import com.pavbhaji.pos.data.KitchenDto
import com.pavbhaji.pos.ui.ConnBanner
import com.pavbhaji.pos.ui.EmptyHint
import com.pavbhaji.pos.ui.StatusChip
import com.pavbhaji.pos.ui.WarnAmber
import com.pavbhaji.pos.ui.ageMinutes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@Composable
fun KitchenScreen(onLogout: () -> Unit, embedded: Boolean = false, onBack: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val connected by Deps.live.connected.collectAsState()
    var data by remember { mutableStateOf(KitchenDto()) }
    var tab by remember { mutableStateOf(0) }
    val context = LocalContext.current

    // Kitchen phone should never dim mid-rush.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Beep when a ticket id we've never seen shows up.
    val seenTickets = remember { mutableSetOf<Long>() }
    var firstLoad by remember { mutableStateOf(true) }

    suspend fun refresh() {
        when (val r = Deps.api.kitchen()) {
            is ApiResult.Ok -> {
                val fresh = r.value.tickets.map { it.ticketId }.filter { it !in seenTickets }
                if (fresh.isNotEmpty() && !firstLoad && !embedded) {
                    try {
                        ToneGenerator(AudioManager.STREAM_MUSIC, 90)
                            .startTone(ToneGenerator.TONE_PROP_BEEP2, 500)
                    } catch (_: Exception) {}
                }
                seenTickets.addAll(fresh)
                firstLoad = false
                data = r.value
            }
            is ApiResult.Err -> {}
        }
    }
    LaunchedEffect(Unit) {
        refresh()
        Deps.live.topics.collect { if (it == "kitchen" || it == "tables") refresh() }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(15_000); refresh() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) TextButton(onClick = onBack) { Text("← Back") }
            Text(
                "Kitchen",
                fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            Text(
                Deps.session.staffName, fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onBack == null) TextButton(onClick = onLogout) { Text("Logout") }
        }
        ConnBanner(connected)
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 },
                text = { Text("Tickets (${data.tickets.size})") })
            Tab(selected = tab == 1, onClick = { tab = 1 },
                text = { Text("Batch cook") })
        }

        if (tab == 0) {
            if (data.tickets.isEmpty()) EmptyHint("No open tickets — all caught up 👨‍🍳")
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(data.tickets.size, key = { data.tickets[it].ticketId }) { i ->
                    val tk = data.tickets[i]
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 2.dp
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    tk.tableName, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "  #${tk.orderNo} · ${tk.waiter}",
                                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                val age = ageMinutes(tk.createdAt)
                                Text(
                                    age, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                    color = if ((System.currentTimeMillis() - tk.createdAt) > 15 * 60_000)
                                        MaterialTheme.colorScheme.error else WarnAmber
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            tk.items.forEach { it ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "${it.qty}× ${it.name}",
                                            fontSize = 16.sp, fontWeight = FontWeight.Bold
                                        )
                                        if (it.note.isNotEmpty()) {
                                            Text(
                                                "⚠ ${it.note}", fontSize = 13.sp,
                                                color = MaterialTheme.colorScheme.error,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                    StatusChip(it.status)
                                    val next = when (it.status) {
                                        "pending" -> "preparing" to "Start"
                                        "preparing" -> "ready" to "Ready ✓"
                                        else -> null
                                    }
                                    if (next != null) {
                                        TextButton(onClick = {
                                            scope.launch {
                                                Deps.api.itemStatus(it.id, next.first); refresh()
                                            }
                                        }) {
                                            Text(
                                                next.second, fontWeight = FontWeight.Bold,
                                                color = if (next.first == "ready") Color(0xFF2E7D32)
                                                else MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                items(1) { Spacer(Modifier.height(40.dp)) }
            }
        } else {
            // Batch view: everything still to cook, summed per dish across tables.
            if (data.batch.isEmpty()) EmptyHint("Nothing pending 🎉")
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(data.batch.size, key = { data.batch[it].name }) { i ->
                    val b = data.batch[i]
                    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(b.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            Text(
                                "× ${b.qty}", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
}
