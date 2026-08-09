package com.pavbhaji.pos.ui.waiter

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.pavbhaji.pos.ui.BhajiRed
import com.pavbhaji.pos.ui.ConnBanner
import com.pavbhaji.pos.ui.GoodGreen
import com.pavbhaji.pos.ui.HeaderTextAction
import com.pavbhaji.pos.ui.S
import com.pavbhaji.pos.ui.WarnAmber
import com.pavbhaji.pos.ui.ageMinutes
import com.pavbhaji.pos.ui.items as itemsLabel
import com.pavbhaji.pos.ui.rupees
import kotlinx.coroutines.delay

// Manager's home: two tabs. Tables = punch orders & collect; Ready = the
// table-grouped carry queue. Also embedded inside the admin app as "Floor".
// (Waiters never see this — their whole app is the flat ServeScreen.)
@Composable
fun ManagerHome(onLogout: () -> Unit, onBack: (() -> Unit)? = null) {
    var selected by remember { mutableStateOf<TableStateDto?>(null) }
    var tab by remember { mutableIntStateOf(0) }
    var readyCount by remember { mutableIntStateOf(0) }

    suspend fun countReady() {
        when (val r = Deps.api.pickup()) {
            is ApiResult.Ok -> readyCount = r.value.lines.size
            is ApiResult.Err -> {}
        }
    }
    LaunchedEffect(Unit) {
        countReady()
        Deps.live.topics.collect { if (it == "kitchen" || it == "tables") countReady() }
    }
    LaunchedEffect(Unit) { while (true) { delay(20_000); countReady() } }

    val sel = selected
    if (sel != null) {
        TableOrderScreen(tableId = sel.id, tableName = sel.name, onBack = { selected = null })
        return
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            if (tab == 0) {
                TablesScreen(onOpenTable = { selected = it }, onLogout = onLogout, onBack = onBack)
            } else {
                ReadyScreen(onLogout = onLogout, showLogout = onBack == null)
            }
        }
        NavigationBar(containerColor = Color(0xFFFFFDFB)) {
            NavigationBarItem(
                selected = tab == 0, onClick = { tab = 0 },
                icon = { Text("▦", fontSize = 17.sp) },
                label = { Text(S.tablesTitle, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                colors = NavigationBarItemDefaults.colors(
                    selectedTextColor = BhajiRed, indicatorColor = Color(0xFFFFE0CC)
                )
            )
            NavigationBarItem(
                selected = tab == 1, onClick = { tab = 1 },
                icon = {
                    BadgedBox(badge = {
                        if (readyCount > 0) Badge(containerColor = GoodGreen) {
                            Text("$readyCount", fontSize = 10.sp, color = Color.White)
                        }
                    }) { Text("🛎", fontSize = 17.sp) }
                },
                label = { Text(S.readyTitle, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                colors = NavigationBarItemDefaults.colors(
                    selectedTextColor = BhajiRed, indicatorColor = Color(0xFFFFE0CC)
                )
            )
        }
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
            title = S.tablesTitle,
            subtitle = S.occupiedSub(Deps.session.staffName, busy, tables.size),
            onBack = onBack,
            actions = { if (onBack == null) HeaderTextAction(S.logout, onLogout) }
        )
        ConnBanner(connected)
        if (loadError != null) {
            Text(
                loadError!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp)
            )
        }
        // Dine-in first, parcel slots in their own section below — the admin
        // (or anyone) punches walk-up orders on P1/P2/… without eating a table.
        val dine = tables.filter { it.kind != "parcel" }
        val parcel = tables.filter { it.kind == "parcel" }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(160.dp),
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (parcel.isNotEmpty()) {
                item(key = "hdrDine", span = { GridItemSpan(maxLineSpan) }) { GridSection(S.gridTables) }
            }
            items(dine, key = { it.id }) { t ->
                TableCard(t, onClick = { onOpenTable(t) })
            }
            if (parcel.isNotEmpty()) {
                item(key = "hdrParcel", span = { GridItemSpan(maxLineSpan) }) { GridSection(S.gridParcel) }
                items(parcel, key = { it.id }) { t ->
                    TableCard(t, onClick = { onOpenTable(t) })
                }
            }
        }
    }
}

@Composable
private fun GridSection(text: String) {
    Text(
        text,
        fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

@Composable
private fun Pill(text: String, bg: Color, fg: Color) {
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(
            text, color = fg, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

// Card colour = the waiter's NEXT MOVE, not raw kitchen status:
//   quiet   = free table            amber = kitchen has it, nothing to do
//   green   = plates on the ready line — go get them
//   blue    = everything served — collect the money and close
@Composable
private fun TableCard(t: TableStateDto, onClick: () -> Unit) {
    val o = t.order
    val hasReady = (o?.readyCount ?: 0) > 0
    val cooking = (o?.pendingCount ?: 0) > 0
    val collect = o != null && !hasReady && !cooking

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = when {
            o == null -> Color(0xFFFBF7F1)
            hasReady -> Color(0xFFE4F6EA)
            cooking -> Color(0xFFFFF3E2)
            else -> Color(0xFFE9F0FB)
        },
        border = when {
            hasReady -> BorderStroke(2.dp, Color(0xFF59B87E))
            cooking -> BorderStroke(1.dp, Color(0xFFF2C88F))
            collect -> BorderStroke(1.dp, Color(0xFF8FB2E4))
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
                Text(S.freeTable, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(
                    if (com.pavbhaji.pos.ui.pricesVisible()) "${rupees(o.total)} · ${itemsLabel(o.itemCount)}"
                    else itemsLabel(o.itemCount),
                    fontSize = 15.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${ageMinutes(o.openedAt)} · ${o.openedByName}",
                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    when {
                        hasReady -> Pill(S.readyPickup(o.readyCount), Color(0xFF1E8F53), Color.White)
                        cooking -> Pill(S.inKitchen, Color(0xFFF5E3C2), WarnAmber)
                        else -> Pill(
                            if (com.pavbhaji.pos.ui.pricesVisible()) S.collectAmt(rupees(o.total)) else S.collect,
                            Color(0xFF2F6FCC), Color.White
                        )
                    }
                }
            }
        }
    }
}
