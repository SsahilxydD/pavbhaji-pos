package com.pavbhaji.pos.ui.parcel

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.pavbhaji.pos.data.CategoryDto
import com.pavbhaji.pos.data.NewTicketLine
import com.pavbhaji.pos.data.TableStateDto
import com.pavbhaji.pos.ui.AppHeader
import com.pavbhaji.pos.ui.BigButton
import com.pavbhaji.pos.ui.CardLine
import com.pavbhaji.pos.ui.ConnBanner
import com.pavbhaji.pos.ui.EmptyState
import com.pavbhaji.pos.ui.ErrorBanner
import com.pavbhaji.pos.ui.HeaderTextAction
import com.pavbhaji.pos.ui.Pb
import com.pavbhaji.pos.ui.Pill
import com.pavbhaji.pos.ui.QtyStepper
import com.pavbhaji.pos.ui.S
import com.pavbhaji.pos.ui.SectionTitle
import com.pavbhaji.pos.ui.ageMinutes
import com.pavbhaji.pos.ui.dishColour
import com.pavbhaji.pos.ui.items as itemsLabel
import com.pavbhaji.pos.ui.rupees
import com.pavbhaji.pos.ui.waiter.MenuRow
import com.pavbhaji.pos.ui.waiter.TableOrderScreen
import com.pavbhaji.pos.ui.waiter.bhajiColourItems
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

// Parcel manager's home: one tab per waiting customer, exactly like the
// manager's table grid but for takeaway. Tab colour is the promise state —
// yellow = kitchen still cooking, green = everything ready, hand it over,
// blue = handed over, collect and close. Tap a tab for the full order.
@Composable
fun ParcelHome(onLogout: () -> Unit) {
    var selected by remember { mutableStateOf<TableStateDto?>(null) }
    var adding by remember { mutableStateOf(false) }
    var tables by remember { mutableStateOf<List<TableStateDto>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
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

    val sel = selected
    if (sel != null) {
        val customer = sel.order?.customerName ?: ""
        TableOrderScreen(
            tableId = sel.id,
            tableName = if (customer.isEmpty()) sel.name else "$customer · ${sel.name}",
            onBack = { selected = null }
        )
        return
    }
    if (adding) {
        AddParcelScreen(
            onDone = { orderNo ->
                adding = false
                toast = S.parcelPlaced(orderNo)
            },
            onBack = { adding = false }
        )
        return
    }

    val parcels = tables
        .filter { it.kind == "parcel" && it.order != null }
        .sortedBy { it.order!!.openedAt }

    Column(Modifier.fillMaxSize()) {
        AppHeader(
            title = "🥡 " + S.parcelsTitle,
            subtitle = "${Deps.session.staffName} · ${S.openParcels(parcels.size)}",
            actions = { HeaderTextAction(S.logout, onLogout) }
        )
        ConnBanner(connected)
        ErrorBanner(loadError)
        toast?.let {
            Text(
                it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp)
            )
            LaunchedEffect(it) { delay(2500); toast = null }
        }

        if (parcels.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState("🥡", S.noParcels)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(160.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(parcels, key = { it.id }) { t ->
                    ParcelCard(t, onClick = { selected = t }, modifier = Modifier.animateItem())
                }
            }
        }

        Surface(shadowElevation = 10.dp) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) {
                BigButton(
                    label = S.newParcel,
                    onClick = { adding = true },
                    fontSize = 16
                )
            }
        }
    }
}

// The customer's tab. Yellow while anything is still pending in the kitchen,
// green the moment every plate is ready, blue once it's all handed over.
@Composable
private fun ParcelCard(t: TableStateDto, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val o = t.order ?: return
    val cooking = o.pendingCount > 0
    val allReady = !cooking && o.readyCount > 0

    com.pavbhaji.pos.ui.PbCard(
        onClick = onClick,
        corner = 18.dp,
        color = when {
            cooking -> Color(0xFFFFF6C4)  // pending = yellow
            allReady -> Pb.successBg      // everything ready = green
            else -> Pb.infoBg             // handed over — collect & close
        },
        border = when {
            cooking -> BorderStroke(1.5.dp, Color(0xFFE0B400))
            allReady -> BorderStroke(2.dp, Pb.successLine)
            else -> BorderStroke(1.dp, Pb.infoLine)
        },
        elevation = 2.dp,
        modifier = modifier.heightIn(min = 124.dp)
    ) {
        Column(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        o.customerName.ifEmpty { t.name },
                        fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "#${o.orderNo}", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        color = Pb.ink2
                    )
                }
                val sub = listOf(t.name) + (if (o.customerPhone.isNotEmpty()) listOf(o.customerPhone) else emptyList())
                Text(
                    sub.joinToString(" · "),
                    fontSize = 11.sp, color = Pb.ink2,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Column(Modifier.padding(top = 8.dp)) {
                Text(
                    "${rupees(o.total)} · ${itemsLabel(o.itemCount)}",
                    fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(5.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        ageMinutes(o.openedAt),
                        fontSize = 11.sp, color = Pb.ink2,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    when {
                        cooking -> Pill(S.inKitchen, Color(0xFFF0D060), Color(0xFF5D4000))
                        allReady -> Pill(S.allReadyPill, Pb.successDeep, Color.White)
                        else -> Pill(S.collectAmt(rupees(o.total)), Pb.infoDeep, Color.White)
                    }
                }
            }
        }
    }
}

private data class CartLine(
    val menuItemId: Long, val name: String, val price: Long,
    val qty: Long, val note: String
)

// The add-order flow: customer details on top, then the same tap-to-add menu
// the manager uses. One send button — the server allocates a parcel slot,
// opens the order and fires the KOT in a single transaction.
@Composable
fun AddParcelScreen(onDone: (Long) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val connected by Deps.live.connected.collectAsState()

    var custName by remember { mutableStateOf("") }
    var custPhone by remember { mutableStateOf("") }
    var selColourKey by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf<List<CategoryDto>>(emptyList()) }
    val cart = remember { mutableStateListOf<CartLine>() }
    var clientTicketId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var noteEditIdx by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        when (val m = Deps.api.menu()) {
            is ApiResult.Ok -> menu = m.value.menu
            is ApiResult.Err -> {}
        }
    }

    fun addToCart(id: Long, name: String, price: Long) {
        val i = cart.indexOfFirst { it.menuItemId == id && it.note.isEmpty() }
        if (i >= 0) cart[i] = cart[i].copy(qty = cart[i].qty + 1)
        else cart.add(CartLine(id, name, price, 1, ""))
    }

    fun send() {
        if (sending || cart.isEmpty()) return
        if (custName.trim().isEmpty()) { sendError = S.nameNeeded; return }
        sending = true; sendError = null
        scope.launch {
            val lines = cart.map { NewTicketLine(it.menuItemId, it.qty, it.note) }
            when (val r = Deps.api.createParcel(custName.trim(), custPhone.trim(), clientTicketId, lines)) {
                is ApiResult.Ok -> {
                    cart.clear()
                    clientTicketId = UUID.randomUUID().toString()
                    onDone(r.value.orderNo)
                }
                is ApiResult.Err -> sendError = r.message
            }
            sending = false
        }
    }

    val cartQty = remember(cart.toList()) { cart.groupBy { it.menuItemId }.mapValues { e -> e.value.sumOf { it.qty } } }

    Column(Modifier.fillMaxSize()) {
        AppHeader(title = "🥡 " + S.newParcel.trimStart('＋', ' '), subtitle = S.newOrder, onBack = onBack)
        ConnBanner(connected)

        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            // ---- customer details ----
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, CardLine),
                    shadowElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = custName, onValueChange = { custName = it.take(60) },
                            label = { Text(S.customerName) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = custPhone,
                            onValueChange = { v -> custPhone = v.filter { it.isDigit() || it == '+' || it == ' ' }.take(20) },
                            label = { Text(S.customerPhone) },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // ---- cart ----
            if (cart.isNotEmpty()) {
                item {
                    SectionTitle(S.newKot)
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFFFFF3E7),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            cart.forEachIndexed { idx, line ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f).padding(end = 4.dp)) {
                                        Text(line.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                        val sub = listOfNotNull(
                                            rupees(line.price),
                                            if (line.note.isNotEmpty()) "✎ ${line.note}" else null
                                        ).joinToString(" · ")
                                        Text(
                                            sub, fontSize = 12.sp,
                                            color = if (line.note.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.error
                                        )
                                    }
                                    TextButton(
                                        onClick = { noteEditIdx = idx },
                                        contentPadding = PaddingValues(horizontal = 6.dp)
                                    ) { Text(S.noteBtn, fontSize = 12.sp) }
                                    QtyStepper(line.qty) { q ->
                                        if (q <= 0) cart.removeAt(idx)
                                        else cart[idx] = line.copy(qty = q)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ---- menu: colour rail for bhajis, flat lists for extras ----
            item { SectionTitle(S.addItems) }
            bhajiColourItems(
                kitchen = menu.flatMap { c -> c.items.filter { it.station == "kitchen" } },
                selectedKey = selColourKey,
                onSelect = { selColourKey = it },
                cartQty = cartQty,
                onAdd = { m -> addToCart(m.id, m.name, m.price) }
            )
            menu.forEach { c ->
                val counterItems = c.items.filter { it.station == "counter" }
                if (counterItems.isEmpty()) return@forEach
                item {
                    Text(
                        c.name.uppercase(),
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                    )
                }
                counterItems.forEach { m ->
                    item {
                        MenuRow(
                            name = m.name,
                            price = m.price,
                            available = m.available,
                            colour = dishColour(m.colour, m.id),
                            counter = true,
                            inCart = cartQty[m.id] ?: 0,
                            onClick = { if (m.available) addToCart(m.id, m.name, m.price) }
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }

        // ---- send bar ----
        Surface(shadowElevation = 10.dp) {
            Column(
                Modifier.fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                sendError?.let {
                    Text(
                        it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                val count = cart.sumOf { it.qty }
                val total = cart.sumOf { it.qty * it.price }
                BigButton(
                    label = when {
                        sendError != null && cart.isNotEmpty() -> S.tryAgain(itemsLabel(count))
                        cart.isEmpty() -> S.sendParcel
                        else -> "${S.sendParcel}  ${itemsLabel(count)} · ${rupees(total)}"
                    },
                    onClick = { send() },
                    enabled = cart.isNotEmpty(),
                    loading = sending,
                    fontSize = 16
                )
            }
        }
    }

    noteEditIdx?.let { idx ->
        var text by remember { mutableStateOf(cart.getOrNull(idx)?.note ?: "") }
        AlertDialog(
            onDismissRequest = { noteEditIdx = null },
            title = { Text(S.noteFor(cart.getOrNull(idx)?.name ?: "")) },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text(S.notePlaceholder) },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    cart.getOrNull(idx)?.let { cart[idx] = it.copy(note = text.trim()) }
                    noteEditIdx = null
                }) { Text(S.save) }
            },
            dismissButton = { TextButton(onClick = { noteEditIdx = null }) { Text(S.back) } }
        )
    }
}
