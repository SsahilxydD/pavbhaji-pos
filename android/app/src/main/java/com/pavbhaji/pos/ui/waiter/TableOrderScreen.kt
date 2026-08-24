package com.pavbhaji.pos.ui.waiter

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyListScope
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
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
import com.pavbhaji.pos.ui.BigButton
import com.pavbhaji.pos.ui.CardLine
import com.pavbhaji.pos.ui.ConnBanner
import com.pavbhaji.pos.ui.ErrorBanner
import com.pavbhaji.pos.ui.Pb
import com.pavbhaji.pos.ui.QtyStepper
import com.pavbhaji.pos.ui.ReasonDialog
import com.pavbhaji.pos.ui.S
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
    var noteEditIdx by remember { mutableStateOf<Int?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    var loadError by remember { mutableStateOf<String?>(null) }
    suspend fun refresh() {
        when (val r = Deps.api.tableOrder(tableId)) {
            is ApiResult.Ok -> { order = r.value.order; loadError = null }
            is ApiResult.Err -> loadError = r.message
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
                    toast = S.sentToKitchenToast
                }
                is ApiResult.Err -> sendError = r.message
            }
            sending = false
        }
    }

    // How many of each menu item is sitting in the cart (for the badges).
    val cartQty = remember(cart.toList()) { cart.groupBy { it.menuItemId }.mapValues { e -> e.value.sumOf { it.qty } } }

    Column(Modifier.fillMaxSize()) {
        val o = order
        AppHeader(
            title = tableName,
            subtitle = when {
                o == null -> S.newOrder
                pricesVisible() -> "${S.orderNo(o.orderNo)} · ${rupees(o.total)} · ${o.openedByName}"
                else -> "${S.orderNo(o.orderNo)} · ${o.openedByName}"
            },
            onBack = onBack
        )
        ConnBanner(connected)
        ErrorBanner(loadError)
        toast?.let { LaunchedEffect(it) { kotlinx.coroutines.delay(2500); toast = null } }

        // Opening a table shows the ticket first: what the kitchen already
        // has sits at the top, the cart and menu follow underneath. No
        // auto-scroll — the waiter decides where to look.
        val listState = rememberLazyListState()

        Box(Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                state = listState
            ) {
                // ---- existing tickets ----
                if (o != null) {
                    item(key = "hdrSent") { SectionTitle(S.sentToKitchen) }
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
                                // No serve buttons here: marking served is the
                                // waiter's job alone, from their own carry list.
                                SentItemRow(
                                    it,
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
                    item(key = "cart") {
                        SectionTitle(S.newKot)
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = Color(0xFFFFF3E7),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Pb.warnLine),
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

                // ---- menu picker: one straight list per menu category —
                // bhajis and extras/drinks under the same headers, in menu
                // order. No colour grouping; every dish is one tap away. ----
                item(key = "hdrAdd") { SectionTitle(S.addItems) }
                menu.forEach { c ->
                    if (c.items.isEmpty()) return@forEach
                    item(key = "cat${c.id}") {
                        Text(
                            c.name.uppercase(),
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                        )
                    }
                    c.items.forEach { m ->
                        item(key = "menu${m.id}") {
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
                item(key = "tail") { Spacer(Modifier.height(16.dp)) }
            }

            // A "sent ✓" note floats over the list instead of shoving it down.
            toast?.let {
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardLine),
                    shadowElevation = 6.dp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                ) {
                    Text(
                        it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
            }
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
                    val count = cart.sumOf { it.qty }
                    val total = cart.sumOf { it.qty * it.price }
                    BigButton(
                        label = if (sendError != null) S.tryAgain(itemsLabel(count))
                        else S.sendToKitchen(itemsLabel(count)) +
                            if (pricesVisible()) " · ${rupees(total)}" else "",
                        onClick = { send() },
                        loading = sending,
                        fontSize = 16
                    )
                } else if (o != null) {
                    // Closing belongs to the cashier, after they generate the
                    // bill — this screen only punches and watches.
                    Text(
                        S.cashierCloses,
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                } else {
                    Text(
                        S.tapToStart,
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
            title = S.cancelQ(target.qty, target.name),
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

// Shared with the parcel manager's add-order screen — same one-row-per-dish,
// tap-equals-plus-one picker everywhere an order is punched.
@Composable
fun MenuRow(
    name: String, price: Long, available: Boolean,
    colour: Color, counter: Boolean, inCart: Long,
    onClick: () -> Unit
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && available) 0.975f else 1f,
        spring(dampingRatio = 0.7f, stiffness = 1100f), label = "menuRow"
    )
    Surface(
        onClick = {
            if (available) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        },
        enabled = available,
        interactionSource = interaction,
        shape = RoundedCornerShape(14.dp),
        color = when {
            !available -> MaterialTheme.colorScheme.surfaceVariant
            inCart > 0 -> Color(0xFFFFE4CF)
            else -> MaterialTheme.colorScheme.surface
        },
        border = if (inCart > 0) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary)
        else androidx.compose.foundation.BorderStroke(1.dp, CardLine),
        shadowElevation = if (available) 1.dp else 0.dp,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
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
                !available -> S.outOfStock
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
                if (counter && it.status == "pending") S.justCarry else null,
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
        if (canCancel) {
            TextButton(onClick = onCancel) { Text("✕", color = MaterialTheme.colorScheme.error) }
        }
    }
}
