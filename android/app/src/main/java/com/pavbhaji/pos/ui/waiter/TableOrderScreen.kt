package com.pavbhaji.pos.ui.waiter

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.pavbhaji.pos.ui.AppHeader
import com.pavbhaji.pos.ui.ConfirmDialog
import com.pavbhaji.pos.ui.ConnBanner
import com.pavbhaji.pos.ui.QtyStepper
import com.pavbhaji.pos.ui.ReasonDialog
import com.pavbhaji.pos.ui.SectionTitle
import com.pavbhaji.pos.ui.StatusChip
import com.pavbhaji.pos.ui.WarnAmber
import com.pavbhaji.pos.ui.clockTime
import com.pavbhaji.pos.ui.dishColour
import com.pavbhaji.pos.ui.items as itemsLabel
import com.pavbhaji.pos.ui.pricesVisible
import com.pavbhaji.pos.ui.rupees
import kotlinx.coroutines.launch
import java.util.UUID

private data class CartLine(
    val menuItemId: Long, val name: String, val price: Long,
    val qty: Long, val note: String
)

@Composable
fun TableOrderScreen(tableId: Long, tableName: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val connected by Deps.live.connected.collectAsState()
    val isAdmin = Deps.session.staffRole == "admin"

    var menu by remember { mutableStateOf<List<CategoryDto>>(emptyList()) }
    var order by remember { mutableStateOf<OrderDetailDto?>(null) }
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

    // How many of each menu item is sitting in the cart (for the badges).
    val cartQty = remember(cart.toList()) { cart.groupBy { it.menuItemId }.mapValues { e -> e.value.sumOf { it.qty } } }

    Column(Modifier.fillMaxSize()) {
        val o = order
        AppHeader(
            title = tableName,
            subtitle = when {
                o == null -> "new order"
                pricesVisible() -> "order #${o.orderNo} · ${rupees(o.total)} · ${o.openedByName}"
                else -> "order #${o.orderNo} · ${o.openedByName}"
            },
            onBack = onBack
        )
        ConnBanner(connected)
        toast?.let {
            Text(
                it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp)
            )
            LaunchedEffect(it) { kotlinx.coroutines.delay(2500); toast = null }
        }

        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            // ---- existing tickets ----
            if (o != null) {
                item { SectionTitle("SENT TO KITCHEN") }
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
                            // Waiters serve what's READY (from the kitchen's buckets) and
                            // hand counter items over directly; admin can force anything.
                            SentItemRow(
                                it,
                                canServe = it.status == "ready" ||
                                    (it.status == "pending" && it.station == "counter") ||
                                    (isAdmin && (it.status == "pending" || it.status == "preparing")),
                                onServe = {
                                    scope.launch { Deps.api.itemStatus(it.id, "served"); refresh() }
                                },
                                canCancel = it.status != "cancelled" && it.status != "served" &&
                                    (it.status == "pending" || isAdmin),
                                onCancel = { cancelTarget = it }
                            )
                        }
                    }
                }
            }

            // ---- cart (new KOT) ----
            if (cart.isNotEmpty()) {
                item {
                    SectionTitle("NEW KOT — NOT SENT YET")
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
                                    Column(
                                        Modifier.weight(1f)
                                            .padding(end = 4.dp)
                                    ) {
                                        Text(line.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                        val sub = listOfNotNull(
                                            if (pricesVisible()) rupees(line.price) else null,
                                            if (line.note.isNotEmpty()) "✎ ${line.note}" else null
                                        ).joinToString(" · ")
                                        if (sub.isNotEmpty()) Text(
                                            sub,
                                            fontSize = 12.sp,
                                            color = if (line.note.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                                            else MaterialTheme.colorScheme.error
                                        )
                                    }
                                    TextButton(
                                        onClick = { noteEditIdx = idx },
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
                                    ) { Text("note", fontSize = 12.sp) }
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

            // ---- menu picker: owner's rule — keep the waiter dead simple,
            // every dish is one full-width row, tap = +1, no tabs, no grid ----
            item { SectionTitle("ADD ITEMS") }
            menu.forEach { c ->
                if (menu.size > 1) item {
                    Text(
                        c.name.uppercase(),
                        fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                    )
                }
                c.items.forEach { m ->
                    item {
                        MenuRow(
                            name = m.name,
                            price = m.price,
                            available = m.available,
                            colour = dishColour(m.colour, m.id),
                            counter = m.station == "counter",
                            inCart = cartQty[m.id] ?: 0,
                            onClick = { if (m.available) addToCart(m.id, m.name, m.price) }
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }

        // ---- bottom action bar ----
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
                if (cart.isNotEmpty()) {
                    Button(
                        onClick = { send() },
                        enabled = !sending,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        val count = cart.sumOf { it.qty }
                        val total = cart.sumOf { it.qty * it.price }
                        Text(
                            when {
                                sending -> "Sending…"
                                sendError != null -> "Try again — ${itemsLabel(count)}"
                                else -> "Send to kitchen 🍳  ${itemsLabel(count)}" +
                                    if (pricesVisible()) " · ${rupees(total)}" else ""
                            },
                            fontSize = 16.sp, fontWeight = FontWeight.ExtraBold
                        )
                    }
                } else if (o != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val servable = o.tickets.any { t -> t.items.any { it.status == "pending" || it.status == "ready" } }
                        OutlinedButton(
                            onClick = { scope.launch { Deps.api.serveAll(o.id); refresh() } },
                            enabled = servable,
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(16.dp)
                        ) { Text("All served ✓", fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                        Button(
                            onClick = { confirmClose = true },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) {
                            Text(
                                if (pricesVisible()) "Close · ${rupees(o.total)}" else "Close table",
                                fontSize = 15.sp, fontWeight = FontWeight.ExtraBold
                            )
                        }
                    }
                } else {
                    Text(
                        "Tap items above to start this table's order",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
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
        ConfirmDialog(
            title = "Close $tableName?",
            body = (if (pricesVisible()) "Total: ${rupees(order?.total ?: 0)}\n" else "") +
                "Collect payment before closing.",
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
private fun MenuRow(
    name: String, price: Long, available: Boolean,
    colour: Color, counter: Boolean, inCart: Long,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = available,
        shape = RoundedCornerShape(12.dp),
        color = when {
            !available -> MaterialTheme.colorScheme.surfaceVariant
            inCart > 0 -> Color(0xFFFFE4CF)
            else -> MaterialTheme.colorScheme.surface
        },
        border = if (inCart > 0) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        shadowElevation = if (available) 1.dp else 0.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(10.dp)
                    .background(colour.copy(alpha = if (available) 1f else 0.4f), CircleShape)
            )
            Text(
                name, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                color = if (available) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 9.dp).weight(1f)
            )
            val sub = when {
                !available -> "out of stock"
                counter -> if (pricesVisible()) "${rupees(price)} · ✋" else "✋"
                else -> if (pricesVisible()) rupees(price) else ""
            }
            if (sub.isNotEmpty()) Text(
                sub, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
            if (inCart > 0) {
                Surface(color = MaterialTheme.colorScheme.primary, shape = CircleShape, modifier = Modifier.size(26.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("$inCart", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }
        }
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
    val counter = it.station == "counter"
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(10.dp).padding(end = 0.dp)
                .background(
                    dishColour(it.colour, it.menuItemId).copy(alpha = if (it.status == "cancelled") 0.35f else 1f),
                    RoundedCornerShape(3.dp)
                )
        )
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                "${it.qty}× ${it.name}",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                textDecoration = if (it.status == "cancelled") TextDecoration.LineThrough else null,
                color = if (it.status == "cancelled") MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface
            )
            val sub = listOfNotNull(
                if (pricesVisible()) rupees(it.price * it.qty) else null,
                if (counter && it.status == "pending") "✋ you serve this" else null,
                it.note.ifEmpty { null },
                it.cancelReason.ifEmpty { null }
            ).joinToString(" · ")
            if (sub.isNotEmpty()) Text(
                sub, fontSize = 12.sp,
                color = if (counter && it.status == "pending") WarnAmber
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        StatusChip(it.status)
        if (canServe) {
            TextButton(onClick = onServe) {
                Text(
                    if (counter && it.status == "pending") "✋ serve" else "serve ✓",
                    fontSize = 12.sp, color = Color(0xFF2E7D32)
                )
            }
        }
        if (canCancel) {
            TextButton(onClick = onCancel) { Text("✕", color = MaterialTheme.colorScheme.error) }
        }
    }
}
