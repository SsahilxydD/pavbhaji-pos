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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.pavbhaji.pos.ui.S
import com.pavbhaji.pos.ui.WarnAmber
import com.pavbhaji.pos.ui.ageMinutes
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
            title = S.readyTitle,
            subtitle = when {
                lines.isEmpty() -> S.nothingToCarry
                groups.size == 1 -> S.toCarryOneTable(lines.size)
                else -> S.toCarryTables(lines.size, groups.size)
            },
            actions = { if (showLogout) HeaderTextAction(S.logout, onLogout) }
        )
        ConnBanner(connected)
        toast?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp))
        }
        if (lines.isEmpty()) {
            EmptyHint(S.allCarried)
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
                                if (busy) "…" else S.allN(g.lines.size),
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
                                if (l.station == "counter") S.justCarry else null
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
                                if (g.lines.size == 1) S.servedTick else "✓",
                                fontSize = 12.sp, fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                }
            }
        }
    }
}

// The waiter's ENTIRE app: one flat vertical list — every plate to carry right
// now, oldest first. Each row reads like a call-out: "2× Cheese Bhaji → T4 ✓".
// Waiters never take orders (that's the manager's job); they only serve, so
// there are no tabs, no tables grid, nothing else to learn.
@Composable
fun ServeScreen(onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    val connected by Deps.live.connected.collectAsState()
    var lines by remember { mutableStateOf<List<PickupLineDto>>(emptyList()) }
    var served by remember { mutableStateOf<List<PickupLineDto>>(emptyList()) }
    var toast by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        when (val r = Deps.api.pickup()) {
            is ApiResult.Ok -> { lines = r.value.lines; served = r.value.served }
            is ApiResult.Err -> {}
        }
    }
    LaunchedEffect(Unit) {
        refresh()
        Deps.live.topics.collect { if (it == "kitchen" || it == "tables" || it == "menu") refresh() }
    }
    LaunchedEffect(Unit) { while (true) { delay(15_000); refresh() } }
    toast?.let { LaunchedEffect(it) { delay(2500); toast = null } }

    val sorted = lines.sortedWith(compareBy({ it.sinceMs }, { it.itemId }))
    // History renders oldest→newest, so the most recent serve sits directly
    // above the live list — one small scroll up answers "what did I just take?".
    val history = served.reversed()

    // Keep the live list anchored at the top of the screen even though history
    // sits above it; re-anchor on changes only when the waiter isn't up reading.
    val listState = rememberLazyListState()
    var lastBoundary by remember { mutableIntStateOf(-1) }
    val boundary = if (history.isEmpty()) 0 else history.size + 2 // two labels bracket history
    LaunchedEffect(boundary) {
        if (boundary != lastBoundary) {
            if (lastBoundary == -1 || listState.firstVisibleItemIndex >= lastBoundary) {
                listState.scrollToItem(boundary)
            }
            lastBoundary = boundary
        }
    }

    Column(Modifier.fillMaxSize()) {
        AppHeader(
            title = S.serveTitle,
            subtitle = if (lines.isEmpty()) S.nothingToCarry else S.toCarry(lines.size),
            actions = { HeaderTextAction(S.logout, onLogout) }
        )
        ConnBanner(connected)
        toast?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp))
        }
        if (lines.isEmpty()) {
            EmptyHint(S.allCarried)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (history.isNotEmpty()) {
                item(key = "hdrServed") { SectionLabel(S.servedSection) }
                items(history.size, key = { "h${history[it].itemId}" }) { i ->
                    HistoryRow(history[i])
                }
                item(key = "hdrNow") { SectionLabel(S.serveNow) }
            }
            items(sorted.size, key = { "a${sorted[it].itemId}" }) { i ->
                val l = sorted[i]
                ServeRow(
                    l,
                    onServe = {
                        scope.launch {
                            when (val r = Deps.api.itemStatus(l.itemId, "served")) {
                                is ApiResult.Ok -> lines = lines.filter { it.itemId != l.itemId }
                                is ApiResult.Err -> toast = r.message
                            }
                            refresh()
                        }
                    }
                )
            }
            items(1) { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp)
    )
}

// A plate that already went out: greyed, no button — pure memory. The table
// number stays loud because "where did that plate go?" is the question here.
@Composable
private fun HistoryRow(l: PickupLineDto) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFFF5F1EA),
        border = BorderStroke(1.dp, Color(0xFFE9E2D6))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("✓", color = GoodGreen, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                Text(
                    "${l.qty}× ${l.name}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    listOfNotNull(ageMinutes(l.sinceMs), l.servedBy.ifEmpty { null }).joinToString(" · "),
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                l.tableName, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun ServeRow(l: PickupLineDto, onServe: () -> Unit) {
    val counter = l.station == "counter"
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (counter) Color(0xFFFFFBF2) else Color.White,
        border = BorderStroke(1.dp, Color(0xFFEADFD2))
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(11.dp).background(dishColour(l.colour, l.itemId), RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                Text("${l.qty}× ${l.name}", fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                val sub = listOfNotNull(
                    if (l.note.isNotEmpty()) "⚠ ${l.note}" else null,
                    if (counter) S.justCarry else null
                ).joinToString(" · ")
                if (sub.isNotEmpty()) Text(
                    sub, fontSize = 11.sp, maxLines = 1,
                    color = if (l.note.isNotEmpty()) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            ReadyAge(l.sinceMs)
            // The table number is what a waiter scans the list for — loudest thing in the row.
            Text(
                l.tableName, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
            Button(
                onClick = onServe,
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = if (counter) WarnAmber else GoodGreen)
            ) {
                Text("✓", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
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
