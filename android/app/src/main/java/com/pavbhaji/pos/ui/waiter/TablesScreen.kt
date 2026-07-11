package com.pavbhaji.pos.ui.waiter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pavbhaji.pos.Deps
import com.pavbhaji.pos.data.ApiResult
import com.pavbhaji.pos.data.TableStateDto
import com.pavbhaji.pos.ui.AppHeader
import com.pavbhaji.pos.ui.ConnBanner
import com.pavbhaji.pos.ui.GoodGreen
import com.pavbhaji.pos.ui.HeaderTextAction
import com.pavbhaji.pos.ui.WarnAmber
import com.pavbhaji.pos.ui.ageMinutes
import com.pavbhaji.pos.ui.items as itemsLabel
import com.pavbhaji.pos.ui.rupees
import kotlinx.coroutines.delay

// Waiter's home. Also embedded inside the admin app as the "Floor" view:
// then onBack is set and the logout button is hidden.
@Composable
fun WaiterHome(onLogout: () -> Unit, onBack: (() -> Unit)? = null) {
    var selected by remember { mutableStateOf<TableStateDto?>(null) }
    val sel = selected
    if (sel != null) {
        TableOrderScreen(tableId = sel.id, tableName = sel.name, onBack = { selected = null })
    } else {
        TablesScreen(onOpenTable = { selected = it }, onLogout = onLogout, onBack = onBack)
    }
}

@Composable
fun TablesScreen(
    onOpenTable: (TableStateDto) -> Unit,
    onLogout: () -> Unit,
    onBack: (() -> Unit)?
) {
    var tables by remember { mutableStateOf<List<TableStateDto>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val connected by Deps.live.connected.collectAsState()

    suspend fun refresh() {
        when (val r = Deps.api.tables()) {
            is ApiResult.Ok -> { tables = r.value.tables; loadError = null }
            is ApiResult.Err -> loadError = r.message
        }
    }
    LaunchedEffect(Unit) {
        refresh()
        Deps.live.topics.collect { if (it == "tables" || it == "kitchen") refresh() }
    }
    LaunchedEffect(Unit) { // fallback poll in case WiFi ate a push
        while (true) { delay(25_000); refresh() }
    }

    val busy = tables.count { it.order != null }
    Column(Modifier.fillMaxSize()) {
        AppHeader(
            title = "Tables",
            subtitle = "${Deps.session.staffName} · $busy of ${tables.size} occupied",
            onBack = onBack,
            actions = { if (onBack == null) HeaderTextAction("Logout", onLogout) }
        )
        ConnBanner(connected)
        if (loadError != null) {
            Text(
                loadError!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(tables, key = { it.id }) { t ->
                TableCard(t, onClick = { onOpenTable(t) })
            }
        }
    }
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color) {
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(
            text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun TableCard(t: TableStateDto, onClick: () -> Unit) {
    val o = t.order
    val hasReady = (o?.readyCount ?: 0) > 0
    val cooking = (o?.pendingCount ?: 0) > 0
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = when {
            o == null -> MaterialTheme.colorScheme.surface
            hasReady -> Color(0xFFE8F5E9)
            else -> Color(0xFFFFEFE3)
        },
        border = when {
            hasReady -> BorderStroke(2.dp, GoodGreen)
            o != null -> BorderStroke(1.dp, Color(0xFFF0C9AE))
            else -> BorderStroke(1.dp, Color(0xFFEDE5DD))
        },
        shadowElevation = if (o != null) 2.dp else 0.dp,
        modifier = Modifier.height(118.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.name, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                if (o != null) {
                    Text(
                        "#${o.orderNo}", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            if (o == null) {
                Text("free", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "${rupees(o.total)} · ${itemsLabel(o.itemCount)}",
                    fontSize = 15.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${ageMinutes(o.openedAt)} · ${o.openedByName}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (hasReady) Pill("${o.readyCount} READY", Color(0xFFC8E6C9), Color(0xFF1B5E20))
                    else if (cooking) Pill("${o.pendingCount} cooking", Color(0xFFFFECB3), WarnAmber)
                }
            }
        }
    }
}
