package com.pavbhaji.pos.ui.waiter

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pavbhaji.pos.Deps
import com.pavbhaji.pos.data.ApiResult
import com.pavbhaji.pos.data.TableStateDto
import com.pavbhaji.pos.ui.AppHeader
import com.pavbhaji.pos.ui.BhajiRed
import com.pavbhaji.pos.ui.CardLine
import com.pavbhaji.pos.ui.ErrorBanner
import com.pavbhaji.pos.ui.LogoutAction
import com.pavbhaji.pos.ui.ListSkeleton
import com.pavbhaji.pos.ui.Pb
import com.pavbhaji.pos.ui.Pill
import com.pavbhaji.pos.ui.PbCard
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
    // Saved state: if Android kills the app in the background, the manager
    // comes back to the same tab and the same open table (cart included —
    // TableOrderScreen saves its own).
    var selId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selName by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
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

    val sel = selId
    if (sel != null) {
        TableOrderScreen(tableId = sel, tableName = selName, onBack = { selId = null })
        return
    }
    // A manager on the Ready tab: Back goes to Tables first, like any
    // bottom-tab app, instead of closing the POS.
    if (onBack == null) BackHandler(enabled = tab == 1) { tab = 0 }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            if (tab == 0) {
                TablesScreen(onOpenTable = { selName = it.name; selId = it.id }, onLogout = onLogout, onBack = onBack)
            } else {
                ReadyScreen(onLogout = onLogout, showLogout = onBack == null, onBack = onBack)
            }
        }
        NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
            NavigationBarItem(
                selected = tab == 0, onClick = { tab = 0 },
                icon = { Text("▦", fontSize = 18.sp) },
                label = { Text(S.tablesTitle, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                colors = NavigationBarItemDefaults.colors(
                    selectedTextColor = BhajiRed, indicatorColor = Color(0xFFFFE0CC)
                )
            )
            NavigationBarItem(
                selected = tab == 1, onClick = { tab = 1 },
                icon = {
                    BadgedBox(badge = {
                        if (readyCount > 0) Badge(containerColor = Pb.successDeep) {
                            Text("$readyCount", fontSize = 10.sp, color = Color.White)
                        }
                    }) { Text("🛎", fontSize = 18.sp) }
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
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        when (val r = Deps.api.tables()) {
            is ApiResult.Ok -> { tables = r.value.tables; loadError = null; loaded = true }
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
            actions = { if (onBack == null) LogoutAction(onLogout) }
        )
        ErrorBanner(loadError)
        if (!loaded) {
            ListSkeleton(rows = 4, rowHeight = 104.dp)
            return@Column
        }
        // The cashier's grid answers the cashier's question: who is ready to
        // pay. Those tables come first; everything else keeps its place.
        val cashierView = Deps.session.staffRole == "cashier"
        fun ordered(ts: List<TableStateDto>) =
            if (cashierView) ts.sortedByDescending { readyToSettle(it) } else ts
        // Dine-in first, parcel slots in their own section below — the admin
        // (or anyone) punches walk-up orders on P1/P2/… without eating a table.
        val dine = ordered(tables.filter { it.kind != "parcel" })
        val parcel = ordered(tables.filter { it.kind == "parcel" })
        // Three compact cards across a 360 dp phone (was two big ones): the
        // whole floor in about one screen instead of three.
        LazyVerticalGrid(
            columns = GridCells.Adaptive(104.dp),
            contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (parcel.isNotEmpty()) {
                item(key = "hdrDine", span = { GridItemSpan(maxLineSpan) }) { GridSection(S.gridTables) }
            }
            items(dine, key = { it.id }) { t ->
                TableCard(t, cashierView, onClick = { onOpenTable(t) }, modifier = Modifier.animateItem())
            }
            if (parcel.isNotEmpty()) {
                item(key = "hdrParcel", span = { GridItemSpan(maxLineSpan) }) { GridSection(S.gridParcel) }
                items(parcel, key = { it.id }) { t ->
                    TableCard(t, cashierView, onClick = { onOpenTable(t) }, modifier = Modifier.animateItem())
                }
            }
            item(key = "tail", span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun GridSection(text: String) {
    Text(
        text,
        fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = com.pavbhaji.pos.ui.tracking(1.2f),
        color = Pb.ink3,
        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
    )
}

// Everything served, nothing cooking: the money is the only thing left.
private fun readyToSettle(t: TableStateDto): Boolean {
    val o = t.order ?: return false
    return o.readyCount == 0L && o.pendingCount == 0L
}

// Card colour = the reader's NEXT MOVE, not raw kitchen status. Manager:
//   quiet   = free table            amber = kitchen has it, nothing to do
//   green   = plates on the ready line — go get them
//   blue    = everything served — collect the money and close
// Cashier: blue "SETTLE" is the only loud card; anything still cooking or
// waiting to be carried is a calm white "running" card, because carrying
// plates is not the cashier's job.
//
// Compact, three across a 360 dp phone: name + age on top (the age never
// truncates — the name gives way first), money or item count, #order and
// who, then the pill on a row of its own so it can't squeeze the age out.
@Composable
private fun TableCard(t: TableStateDto, cashierView: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val o = t.order
    val hasReady = (o?.readyCount ?: 0) > 0
    val cooking = (o?.pendingCount ?: 0) > 0
    val collect = o != null && !hasReady && !cooking

    PbCard(
        onClick = onClick,
        corner = 16.dp,
        color = when {
            o == null -> Pb.quietCard
            cashierView -> if (collect) Pb.infoBg else Color.White
            hasReady -> Pb.successBg
            cooking -> Pb.warnBg
            else -> Pb.infoBg
        },
        border = when {
            o == null -> BorderStroke(1.dp, Color(0xFFEDE5DD))
            cashierView -> if (collect) BorderStroke(2.dp, Pb.infoLine) else BorderStroke(1.dp, CardLine)
            hasReady -> BorderStroke(2.dp, Pb.successLine)
            cooking -> BorderStroke(1.dp, Pb.warnLine)
            else -> BorderStroke(1.dp, Pb.infoLine)
        },
        elevation = if (o != null) 2.dp else 0.dp,
        modifier = modifier.heightIn(min = 104.dp)
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.name, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (o != null) {
                    Text(
                        ageMinutes(o.openedAt), fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                        color = Pb.ink2, maxLines = 1, softWrap = false,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
            if (o == null) {
                Text(S.freeTable, fontSize = 13.sp, color = Pb.ink3, modifier = Modifier.padding(top = 4.dp))
            } else {
                Text(
                    if (com.pavbhaji.pos.ui.pricesVisible()) rupees(o.total) else itemsLabel(o.itemCount),
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
                Text(
                    // Parcel slots carry the customer's name — that's what
                    // you shout across the counter, not who punched it.
                    "#${o.orderNo} · ${o.customerName.ifEmpty { o.openedByName }}",
                    fontSize = 11.sp, color = Pb.ink2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                when {
                    cashierView && collect -> Pill(S.settlePill, Pb.infoDeep, Color.White)
                    cashierView -> Pill(S.runningPill, Pb.skeleton, Pb.ink2)
                    hasReady -> Pill(S.readyPickup(o.readyCount), Pb.successDeep, Color.White)
                    cooking -> Pill(S.inKitchen, Color(0xFFF5E3C2), WarnAmber)
                    else -> Pill(S.collect, Pb.infoDeep, Color.White)
                }
            }
        }
    }
}
