package com.pavbhaji.pos.ui.waiter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.pavbhaji.pos.ui.ConnBanner
import com.pavbhaji.pos.ui.GoodGreen
import com.pavbhaji.pos.ui.ageMinutes
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
        TablesScreen(
            onOpenTable = { selected = it },
            onLogout = onLogout,
            onBack = onBack
        )
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

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) TextButton(onClick = onBack) { Text("← Back") }
            Text(
                "Tables",
                fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            Text(
                Deps.session.staffName,
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (onBack == null) TextButton(onClick = onLogout) { Text("Logout") }
        }
        ConnBanner(connected)
        if (loadError != null) {
            Text(
                loadError!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp)
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
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
private fun TableCard(t: TableStateDto, onClick: () -> Unit) {
    val o = t.order
    val free = o == null
    val hasReady = (o?.readyCount ?: 0) > 0
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = when {
            free -> MaterialTheme.colorScheme.surface
            hasReady -> Color(0xFFE8F5E9)
            else -> MaterialTheme.colorScheme.primaryContainer
        },
        border = if (hasReady) BorderStroke(2.dp, GoodGreen) else null,
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.name, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                if (o != null) Text("#${o.orderNo}", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(6.dp))
            if (o == null) {
                Text("free", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    "${o.itemCount} items · ${rupees(o.total)}",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
                Text(
                    buildString {
                        append(ageMinutes(o.openedAt))
                        append(" · ")
                        append(o.openedByName)
                        if (hasReady) append(" · ${o.readyCount} READY")
                        else if (o.pendingCount > 0) append(" · ${o.pendingCount} cooking")
                    },
                    fontSize = 12.sp,
                    color = if (hasReady) GoodGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (hasReady) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}
