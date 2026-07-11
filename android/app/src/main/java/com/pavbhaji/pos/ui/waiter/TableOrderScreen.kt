package com.pavbhaji.pos.ui.waiter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pavbhaji.pos.Deps
import com.pavbhaji.pos.data.ApiResult
import com.pavbhaji.pos.data.CategoryDto
import com.pavbhaji.pos.data.NewTicketLine
import com.pavbhaji.pos.data.OrderDetailDto
import com.pavbhaji.pos.data.OrderItemDto
import com.pavbhaji.pos.ui.ConfirmDialog
import com.pavbhaji.pos.ui.ConnBanner
import com.pavbhaji.pos.ui.ReasonDialog
import com.pavbhaji.pos.ui.SectionTitle
import com.pavbhaji.pos.ui.StatusChip
import com.pavbhaji.pos.ui.clockTime
import com.pavbhaji.pos.ui.rupees
import kotlinx.coroutines.launch
import java.util.UUID

private data class CartLine(
    val menuItemId: Long, val name: String, val price: Long,
    var qty: Long, var note: String
)

@Composable
fun TableOrderScreen(tableId: Long, tableName: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val connected by Deps.live.connected.collectAsState()
    val isAdmin = Deps.session.staffRole == "admin"

    var menu by remember { mutableStateOf<List<CategoryDto>>(emptyList()) }
    var order by remember { mutableStateOf<OrderDetailDto?>(null) }
    var selCat by remember { mutableStateOf(0) }
    val cart = remember { mutableStateListOf<CartLine>() }

    // The idempotency key. Fixed for the lifetime of this cart; a new one is
    // made ONLY after the server confirmed the previous ticket.
    var clientTicketId by remember { mutableStateOf(UUID.randomUUID().toString()) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }

    var cancelTarget by remember { mutableStateOf<OrderItemDto?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    var blockers by remember { mutableStateOf<List<String>?>(null) }
    var noteEditIdx by remember { mutableStateOf<Int?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        when (val r = Deps.api.tableOrder(tableId)) {
            is ApiResult.Ok -> order = r.value.order
            is ApiResult.Err -> {}
        }
    }
    LaunchedEffect(Unit) {
        when (val m = Deps.api.menu()) {
            is ApiResult.Ok -> menu = m.value.menu
            is ApiResult.Err -> {}
        }
        refresh()
        Deps.live.topics.collect {
            if (it == "tables" || it == "kitchen") refresh()
            if (it == "menu") {
                when (val m = Deps.api.menu()) {
                    is ApiResult.Ok -> menu = m.value.menu
                    is ApiResult.Err -> {}
                }
            }
        }
    }

    fun addToCart(id: Long, name: String, price: Long) {
        val i = cart.indexOfFirst { it.menuItemId == id && it.note.isEmpty() }
        if (i >= 0) cart[i] = cart[i].copy(qty = cart[i].qty + 1)
        else cart.add(CartLine(id, name, price, 1, ""))
    }

    fun send() {
        if (cart.isEmpty() || sending) return
        sending = true; sendError = null
        scope.launch {
            val lines = cart.map { NewTicketLine(it.menuItemId, it.qty, it.note) }
            when (val r = Deps.api.sendTicket(tableId, clientTicketId, lines)) {
                is ApiResult.Ok -> {
                    order = r.value
                    cart.clear()
                    clientTicketId = UUID.randomUUID().toString()
                    toast = "Sent to kitchen ✓"
                }
                is ApiResult.Err -> sendError = r.message
            }
            sending = false
        }
    }

    fun closeNow() {
        val o = order ?: return
        scope.launch {
            when (val r = Deps.api.closeOrder(o.id)) {
                is ApiResult.Ok -> onBack()
                is ApiResult.Err ->
                    if (r.blockers.isNotEmpty()) blockers = r.blockers
                    else toast = r.message
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("← ") }
            Column(Modifier.weight(1f)) {
                Text(tableName, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                val o = order
                Text(
                    if (o == null) "no order yet"
                    else "order #${o.orderNo} · ${rupees(o.total)}",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (order != null) {
                OutlinedButton(onClick = { confirmClose = true }) { Text("Close table") }
            }
        }
        ConnBanner(connected)
        toast?.let {
            Text(
                it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp)
            )
            LaunchedEffect(it) { kotlinx.coroutines.delay(2500); toast = null }
        }

        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            // ---- existing tickets ----
            val o = order
            if (o != null) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionTitle("SENT TO KITCHEN")
                        Spacer(Modifier.weight(1f))
                        if (o.tickets.any { t -> t.items.any { it.status == "pending" || it.status == "ready" } }) {
                            TextButton(onClick = {
                                scope.launch {
                                    Deps.api.serveAll(o.id); refresh()
                                }
                            }) { Text("All served ✓", fontSize = 13.sp) }
                        }
                    }
                }
                o.tickets.forEach { tk ->
                    item(key = "tk${tk.id}") {
                        Text(
                            "KOT ${tk.ticketNo} · ${clockTime(tk.createdAt)} · ${tk.createdByName}",
                            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                        )
                    }
                    tk.items.forEach { it ->
                        item(key = "it${it.id}") {
                            SentItemRow(
                                it,
                                canServe = it.status == "ready" || it.status == "pending",
                                onServe = {
                                    scope.launch { Deps.api.itemStatus(it.id, "served"); refresh() }
                                },
                                canCancel = it.status != "cancelled" &&
                                    (it.status == "pending" || isAdmin),
                                onCancel = { cancelTarget = it }
                            )
                        }
                    }
                }
            }

            // ---- cart (new KOT) ----
            if (cart.isNotEmpty()) {
                item { SectionTitle("NEW KOT — not sent yet") }
                cart.forEachIndexed { idx, line ->
                    item(key = "cart$idx-${line.menuItemId}-${line.note}") {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(line.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (line.note.isEmpty()) rupees(line.price)
                                    else "${rupees(line.price)} · ${line.note}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { noteEditIdx = idx }) { Text("note", fontSize = 12.sp) }
                            com.pavbhaji.pos.ui.QtyStepper(line.qty) { q ->
                                if (q <= 0) cart.removeAt(idx)
                                else cart[idx] = line.copy(qty = q)
                            }
                        }
                    }
                }
            }

            // ---- menu picker ----
            item {
                SectionTitle("MENU")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    menu.forEachIndexed { i, c ->
                        FilterChip(
                            selected = selCat == i,
                            onClick = { selCat = i },
                            label = { Text(c.name) },
                            modifier = Modifier.padding(end = 6.dp)
                        )
                    }
                }
            }
            val items = menu.getOrNull(selCat)?.items ?: emptyList()
            items.chunked(2).forEach { rowItems ->
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        rowItems.forEach { m ->
                            Surface(
                                onClick = { if (m.available) addToCart(m.id, m.name, m.price) },
                                enabled = m.available,
                                shape = RoundedCornerShape(12.dp),
                                color = if (m.available) MaterialTheme.colorScheme.surface
                                else MaterialTheme.colorScheme.surfaceVariant,
                                shadowElevation = if (m.available) 1.dp else 0.dp,
                                modifier = Modifier.weight(1f).padding(vertical = 4.dp)
                            ) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(
                                        m.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                        color = if (m.available) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        if (m.available) rupees(m.price) else "out of stock",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                        if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }

        // ---- send bar ----
        if (cart.isNotEmpty() || sendError != null) {
            Surface(shadowElevation = 8.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    sendError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 6.dp))
                    }
                    Button(
                        onClick = { send() },
                        enabled = !sending && cart.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) {
                        val count = cart.sumOf { it.qty }
                        val total = cart.sumOf { it.qty * it.price }
                        Text(
                            when {
                                sending -> "Sending…"
                                sendError != null -> "Try again — $count items"
                                else -> "Send to kitchen — $count items · ${rupees(total)}"
                            },
                            fontSize = 16.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    // ---- dialogs ----
    cancelTarget?.let { target ->
        ReasonDialog(
            title = "Cancel ${target.qty}× ${target.name}?",
            onConfirm = { reason ->
                cancelTarget = null
                scope.launch {
                    when (val r = Deps.api.itemCancel(target.id, reason)) {
                        is ApiResult.Ok -> refresh()
                        is ApiResult.Err -> toast = r.message
                    }
                }
            },
            onDismiss = { cancelTarget = null }
        )
    }
    if (confirmClose) {
        val o = order
        ConfirmDialog(
            title = "Close ${tableName}?",
            body = "Total: ${rupees(o?.total ?: 0)}\nCollect payment before closing.",
            confirmLabel = "Close table",
            onConfirm = { confirmClose = false; closeNow() },
            onDismiss = { confirmClose = false }
        )
    }
    blockers?.let { b ->
        AlertDialog(
            onDismissRequest = { blockers = null },
            title = { Text("Can't close yet") },
            text = {
                Column {
                    b.forEach { Text("• $it", fontSize = 14.sp) }
                    Text(
                        "\nMark everything as served, or cancel items with a reason.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    blockers = null
                    scope.launch {
                        order?.let { Deps.api.serveAll(it.id) }
                        closeNow()
                    }
                }) { Text("All served + close") }
            },
            dismissButton = { TextButton(onClick = { blockers = null }) { Text("Back") } }
        )
    }
    noteEditIdx?.let { idx ->
        var text by remember { mutableStateOf(cart.getOrNull(idx)?.note ?: "") }
        AlertDialog(
            onDismissRequest = { noteEditIdx = null },
            title = { Text("Note for ${cart.getOrNull(idx)?.name ?: ""}") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    placeholder = { Text("less spicy, no onion…") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    cart.getOrNull(idx)?.let { cart[idx] = it.copy(note = text.trim()) }
                    noteEditIdx = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { noteEditIdx = null }) { Text("Back") } }
        )
    }
}

@Composable
private fun SentItemRow(
    it: OrderItemDto,
    canServe: Boolean,
    onServe: () -> Unit,
    canCancel: Boolean,
    onCancel: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${it.qty}× ${it.name}",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textDecoration = if (it.status == "cancelled") TextDecoration.LineThrough else null,
                color = if (it.status == "cancelled") MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface
            )
            val sub = buildString {
                append(rupees(it.price * it.qty))
                if (it.note.isNotEmpty()) append(" · ${it.note}")
                if (it.cancelReason.isNotEmpty()) append(" · ${it.cancelReason}")
            }
            Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        StatusChip(it.status)
        if (canServe) {
            TextButton(onClick = onServe) { Text("serve ✓", fontSize = 12.sp, color = Color(0xFF2E7D32)) }
        }
        if (canCancel) {
            TextButton(onClick = onCancel) { Text("✕", color = MaterialTheme.colorScheme.error) }
        }
    }
}
