package com.pavbhaji.pos.ui.waiter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pavbhaji.pos.Deps
import com.pavbhaji.pos.data.ApiResult
import com.pavbhaji.pos.data.PickupLineDto
import com.pavbhaji.pos.ui.AppHeader
import com.pavbhaji.pos.ui.ConnBanner
import com.pavbhaji.pos.ui.EmptyHint
import com.pavbhaji.pos.ui.GoodGreen
import com.pavbhaji.pos.ui.HeaderTextAction
import com.pavbhaji.pos.ui.WarnAmber
import com.pavbhaji.pos.ui.dishColour
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The waiter's carry queue, grouped BY TABLE: every card is one trip — grab
// all of a table's plates together, tap once, done. Cards sort by the oldest
// waiting plate, so the most starved table is always on top. Counter items
// (drinks, extra pav) ride the same card tinted amber: nothing was cooked,
// just carry them.
@Composable
fun ReadyScreen(onLogout: () -> Unit, showLogout: Boolean) {
    val scope = rememberCoroutineScope()
    val connected by Deps.live.connected.collectAsState()
    var lines by remember { mutableStateOf<List<PickupLineDto>>(emptyList()) }
    var toast by remember { mutableStateOf<String?>(null) }
    var busyTable by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        when (val r = Deps.api.pickup()) {
            is ApiResult.Ok -> lines = r.value.lines
            is ApiResult.Err -> {}
        }
    }
    LaunchedEffect(Unit) {
        refresh()
        // "menu" too: an admin recolour must repaint the dish dots live.
        Deps.live.topics.collect { if (it == "kitchen" || it == "tables" || it == "menu") refresh() }
    }
    LaunchedEffect(Unit) { while (true) { delay(15_000); refresh() } }
    toast?.let { LaunchedEffect(it) { delay(2500); toast = null } }

    val groups = lines.groupBy { it.tableName }
        .map { (t, ls) -> ReadyGroup(t, ls.minOf { it.sinceMs }, ls.sortedBy { it.sinceMs }) }
        .sortedWith(compareBy({ it.since }, { it.tableName }))

    Column(Modifier.fillMaxSize()) {
        AppHeader(
            title = "Ready",
            subtitle = when {
                lines.isEmpty() -> "nothing waiting"
                groups.size == 1 -> "${lines.size} to carry · 1 table"
                else -> "${lines.size} to carry · ${groups.size} tables · oldest on top"
            },
            actions = { if (showLogout) HeaderTextAction("Logout", onLogout) }
        )
        ConnBanner(connected)
        toast?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp))
        }
        if (lines.isEmpty()) {
            EmptyHint("All carried out 🎉 New plates appear here the moment a bucket finishes.")
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(groups.size, key = { groups[it].tableName }) { i ->
                val g = groups[i]
                TableCarryCard(
                    g,
                    busy = busyTable == g.tableName,
                    onServeLine = { l ->
                        scope.launch {
                            when (val r = Deps.api.itemStatus(l.itemId, "served")) {
                                is ApiResult.Ok -> lines = lines.filter { it.itemId != l.itemId }
                                is ApiResult.Err -> toast = r.message
                            }
                            refresh()
                        }
                    },
                    onServeAll = {
                        busyTable = g.tableName
                        scope.launch {
                            for (l in g.lines) {
                                when (val r = Deps.api.itemStatus(l.itemId, "served")) {
                                    is ApiResult.Ok -> lines = lines.filter { it.itemId != l.itemId }
                                    is ApiResult.Err -> { toast = r.message; break }
                                }
                            }
                            busyTable = null
                            refresh()
                        }
                    }
                )
            }
            items(1) { Spacer(Modifier.height(20.dp)) }
        }
    }
}

private data class ReadyGroup(
    val tableName: String,
    val since: Long,
    val lines: List<PickupLineDto>
)

@Composable
private fun TableCarryCard(
    g: ReadyGroup,
    busy: Boolean,
    onServeLine: (PickupLineDto) -> Unit,
    onServeAll: () -> Unit
) {
    // Spine green when the kitchen cooked something here; amber when this
    // trip is only counter hand-overs.
    val allCounter = g.lines.all { it.station == "counter" }
    val spine = if (allCounter) Color(0xFFF2A33C) else Color(0xFF1E8F53)
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (allCounter) Color(0xFFFFFBF2) else Color.White,
        border = BorderStroke(1.dp, Color(0xFFEADFD2))
    ) {
        Row(Modifier.height(IntrinsicSize.Min).fillMaxWidth()) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(spine))
            Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(g.tableName, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                    ReadyAge(g.since)
                    if (g.lines.size > 1) {
                        Button(
                            onClick = onServeAll,
                            enabled = !busy,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (allCounter) WarnAmber else GoodGreen
                            ),
                            modifier = Modifier.padding(start = 8.dp)
                        ) {
                            Text(
                                if (busy) "…" else "All ${g.lines.size} ✓",
                                fontSize = 12.sp, fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
                g.lines.forEach { l ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(Modifier.size(11.dp).background(dishColour(l.colour, l.itemId), RoundedCornerShape(4.dp)))
                        Column(Modifier.weight(1f)) {
                            Text("${l.qty}× ${l.name}", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                            val sub = listOfNotNull(
                                if (l.note.isNotEmpty()) "⚠ ${l.note}" else null,
                                if (l.station == "counter") "✋ just carry" else null
                            ).joinToString(" · ")
                            if (sub.isNotEmpty()) Text(
                                sub, fontSize = 11.sp, maxLines = 1,
                                color = if (l.note.isNotEmpty()) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        // One line = full "Served ✓"; in a group each line keeps
                        // a small ✓ so partial carries still work.
                        Button(
                            onClick = { onServeLine(l) },
                            enabled = !busy,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (l.station == "counter") WarnAmber else GoodGreen
                            )
                        ) {
                            Text(
                                if (g.lines.size == 1) "Served ✓" else "✓",
                                fontSize = 12.sp, fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadyAge(sinceMs: Long) {
    val m = ((System.currentTimeMillis() - sinceMs) / 60_000).coerceAtLeast(0)
    val (bg, fg) = when {
        m >= 15 -> Color(0xFFE14B3C) to Color.White
        m >= 8 -> Color(0x33F5A623) to Color(0xFF8A5A00)
        else -> Color.Transparent to GoodGreen
    }
    Surface(color = bg, shape = RoundedCornerShape(7.dp)) {
        Text(
            "${m}m", color = fg, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
