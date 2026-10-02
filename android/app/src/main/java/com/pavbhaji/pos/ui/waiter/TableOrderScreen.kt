package com.pavbhaji.pos.ui.waiter

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pavbhaji.pos.Deps
import com.pavbhaji.pos.data.ApiResult
import com.pavbhaji.pos.data.CategoryDto
import com.pavbhaji.pos.data.NewTicketLine
import com.pavbhaji.pos.data.OrderDetailDto
import com.pavbhaji.pos.data.OrderItemDto
import com.pavbhaji.pos.data.TableStateDto
import com.pavbhaji.pos.ui.AppHeader
import com.pavbhaji.pos.ui.BigButton
import com.pavbhaji.pos.ui.CardLine
import com.pavbhaji.pos.ui.ConfirmDialog
import com.pavbhaji.pos.ui.ErrorBanner
import com.pavbhaji.pos.ui.HoldUpdates
import com.pavbhaji.pos.ui.InkDark
import com.pavbhaji.pos.ui.ListSkeleton
import com.pavbhaji.pos.ui.Pb
import com.pavbhaji.pos.ui.PbCard
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
import com.pavbhaji.pos.ui.swallowTapsAfter
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.UUID

// One line of a cart that hasn't gone to the kitchen yet. Shared with the
// parcel manager's add-order screen.
data class CartLine(
    val menuItemId: Long, val name: String, val price: Long,
    val qty: Long, val note: String
)

// One more of a dish: onto its plain (no-note) line, or a new line. Returns
// true when a NEW line appeared — the cart strip just grew under the finger.
fun cartAddOne(cart: MutableList<CartLine>, id: Long, name: String, price: Long): Boolean {
    val i = cart.indexOfFirst { it.menuItemId == id && it.note.isEmpty() }
    if (i >= 0) { cart[i] = cart[i].copy(qty = cart[i].qty + 1); return false }
    cart.add(CartLine(id, name, price, 1, ""))
    return true
}

// One less of a dish, from its plain line first (a noted "Jain" plate is the
// deliberate one), dropping the line at zero.
fun cartRemoveOne(cart: MutableList<CartLine>, id: Long) {
    val i = cart.indexOfLast { it.menuItemId == id && it.note.isEmpty() }
        .takeIf { it >= 0 } ?: cart.indexOfLast { it.menuItemId == id }
    if (i < 0) return
    if (cart[i].qty <= 1) cart.removeAt(i) else cart[i] = cart[i].copy(qty = cart[i].qty - 1)
}

// A cart outlives Android killing the app in the background (the cashier on
// a 3 GB phone flipping to the UPI app): flattened into the saved state and
// rebuilt on return, together with its idempotency key.
val CartSaver = listSaver<SnapshotStateList<CartLine>, Any>(
    save = { cart -> cart.flatMap { listOf(it.menuItemId, it.name, it.price, it.qty, it.note) } },
    restore = { flat ->
        flat.chunked(5).map {
            CartLine(it[0] as Long, it[1] as String, it[2] as Long, it[3] as Long, it[4] as String)
        }.toMutableStateList()
    }
)

// movable: a dine-in table, whose order can follow its customers to another
// table (onMoved hands back where it went, plus the toast to show there).
@Composable
fun TableOrderScreen(
    tableId: Long,
    tableName: String,
    onBack: () -> Unit,
    movable: Boolean = false,
    notice: String? = null,
    onMoved: (tableId: Long, tableName: String, note: String) -> Unit = { _, _, _ -> }
) {
    val scope = rememberCoroutineScope()
    val isAdmin = Deps.session.staffRole == "admin"

    var menu by remember { mutableStateOf<List<CategoryDto>>(emptyList()) }
    var order by remember { mutableStateOf<OrderDetailDto?>(null) }
    val cart = rememberSaveable(saver = CartSaver) { mutableStateListOf<CartLine>() }
    HoldUpdates(cart.isNotEmpty())

    // The idempotency key. Fixed for the lifetime of this cart; a new one is
    // made ONLY after the server confirmed the previous ticket.
    var clientTicketId by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }

    var cancelTarget by remember { mutableStateOf<OrderItemDto?>(null) }
    var noteEditIdx by remember { mutableStateOf<Int?>(null) }
    var toast by remember { mutableStateOf(notice) }
    var movePicker by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<TableStateDto?>(null) }
    var moving by remember { mutableStateOf(false) }

    var loadError by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    suspend fun loadMenu() {
        when (val m = Deps.api.menu()) {
            is ApiResult.Ok -> menu = m.value.menu
            is ApiResult.Err -> {}
        }
    }
    suspend fun loadOrder() {
        when (val r = Deps.api.tableOrder(tableId)) {
            is ApiResult.Ok -> { order = r.value.order; loadError = null }
            is ApiResult.Err -> loadError = r.message
        }
    }
    suspend fun refresh() {
        if (menu.isEmpty()) loadMenu() // the first try failed (no signal) — try again
        loadOrder()
    }
    LaunchedEffect(Unit) {
        // Ticket and menu load together, and the list isn't drawn until both
        // answered: a list that starts as menu-only stays parked on the menu
        // when the tickets land above it, and the manager never sees what the
        // kitchen already has (and punches it twice).
        coroutineScope {
            launch { loadMenu() }
            launch { loadOrder() }
        }
        loaded = true
        Deps.live.topics.collect {
            if (it == "tables" || it == "kitchen") refresh()
            if (it == "menu") loadMenu()
        }
    }

    // When the bottom bar last grew (first dish in, or a new line): for a
    // moment after that it ignores touches, so the second half of a fast
    // double-tap on the lowest menu row can't hit the strip's "−" or Send.
    var barArmedAt by remember { mutableLongStateOf(0L) }
    fun addToCart(id: Long, name: String, price: Long) {
        if (cartAddOne(cart, id, name, price)) barArmedAt = SystemClock.uptimeMillis()
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

    // Back (the header arrow AND the system gesture — AppHeader wires both to
    // this) never silently drops punched items: with an unsent cart it asks.
    var confirmDiscard by remember { mutableStateOf(false) }
    val leave: () -> Unit = { if (cart.isEmpty()) onBack() else { confirmDiscard = true } }

    Column(Modifier.fillMaxSize()) {
        val o = order
        AppHeader(
            title = tableName,
            subtitle = when {
                o == null -> S.newOrder
                pricesVisible() -> "${S.orderNo(o.orderNo)} · ${rupees(o.total)} · ${o.openedByName}"
                else -> "${S.orderNo(o.orderNo)} · ${o.openedByName}"
            },
            onBack = leave
        )
        ErrorBanner(loadError)
        toast?.let { LaunchedEffect(it) { kotlinx.coroutines.delay(2500); toast = null } }

        // Opening a table shows the ticket first: what the kitchen already
        // has sits at the top, the menu follows underneath. The cart being
        // punched is NOT in this list — it lives in the bottom bar, so a new
        // line can never land off-screen above the menu row just tapped.
        val listState = rememberLazyListState()
        // When the table's order first appears (opened, or this cart was the
        // first KOT) go to the top, where its tickets are. Waits for layout.
        LaunchedEffect(o?.id) { if (o != null && cart.isEmpty()) listState.scrollToItem(0) }

        // Where each menu category starts in the list below (same order as
        // the items: tickets, the ADD ITEMS header, then category by
        // category), for the chips that jump there.
        val catFirstIndex = remember(o, menu) {
            var i = 0
            if (o != null) { i += 1; o.tickets.forEach { i += 1 + it.items.size } }
            i += 1
            buildMap {
                menu.forEach { c -> if (c.items.isNotEmpty()) { put(c.id, i); i += 1 + c.items.size } }
            }
        }
        if (loaded) CategoryChips(menu, catFirstIndex, listState)

        Box(Modifier.weight(1f)) {
            if (!loaded) {
                ListSkeleton(rows = 5, rowHeight = 56.dp)
            } else LazyColumn(
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
                                onClick = { if (m.available) addToCart(m.id, m.name, m.price) },
                                onMinus = { cartRemoveOne(cart, m.id) }
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
                    .swallowTapsAfter(barArmedAt)
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
                    CartStrip(
                        cart,
                        onNote = { noteEditIdx = it },
                        onQty = { idx, q -> if (q <= 0) cart.removeAt(idx) else cart[idx] = cart[idx].copy(qty = q) }
                    )
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
                    // bill — this screen only punches and watches. What it
                    // can do is follow customers who changed table.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            S.cashierCloses,
                            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f).padding(vertical = 6.dp)
                        )
                        if (movable) {
                            OutlinedButton(
                                onClick = { movePicker = true },
                                enabled = !moving,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.padding(start = 10.dp).heightIn(min = 48.dp)
                            ) { Text(S.moveBtn, fontWeight = FontWeight.Bold, maxLines = 1) }
                        }
                    }
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
    if (confirmDiscard) {
        ConfirmDialog(
            title = S.discardCartQ(itemsLabel(cart.sumOf { it.qty })),
            body = S.discardCartBody,
            confirmLabel = S.discardBtn,
            dismissLabel = S.keepBtn,
            destructive = true,
            onConfirm = { confirmDiscard = false; onBack() },
            onDismiss = { confirmDiscard = false }
        )
    }
    if (movePicker) {
        MoveTableDialog(
            fromId = tableId, fromName = tableName,
            onPick = { movePicker = false; moveTarget = it },
            onDismiss = { movePicker = false }
        )
    }
    moveTarget?.let { to ->
        val from = order
        val joining = to.order != null
        ConfirmDialog(
            title = if (joining) S.mergeQ(tableName, to.name) else "$tableName → ${to.name}?",
            body = if (joining) S.mergeBody(to.name, to.order?.orderNo ?: 0)
            else S.moveBody(from?.orderNo ?: 0, to.name),
            confirmLabel = if (joining) S.mergeBtn else S.moveGo,
            onConfirm = {
                moveTarget = null
                if (from != null) {
                    moving = true
                    scope.launch {
                        when (val r = Deps.api.moveOrder(from.id, to.id, joining)) {
                            is ApiResult.Ok -> onMoved(
                                r.value.tableId, r.value.tableName,
                                if (joining) S.mergedToast(r.value.tableName) else S.movedToast(r.value.tableName)
                            )
                            is ApiResult.Err -> { toast = r.message; refresh() }
                        }
                        moving = false
                    }
                }
            },
            onDismiss = { moveTarget = null }
        )
    }
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

// A row of category chips pinned above the menu: one tap jumps the list to
// that category (drinks used to be three flings down a 50-dish list). The
// chip of the category on screen is filled and kept in view.
@Composable
fun CategoryChips(menu: List<CategoryDto>, firstIndex: Map<Long, Int>, listState: LazyListState) {
    val cats = menu.filter { firstIndex.containsKey(it.id) }
    if (cats.size < 2) return
    val scope = rememberCoroutineScope()
    val current by remember(firstIndex, cats) {
        derivedStateOf {
            val top = listState.firstVisibleItemIndex
            cats.lastOrNull { (firstIndex[it.id] ?: Int.MAX_VALUE) <= top }?.id
        }
    }
    val rowState = rememberLazyListState()
    LaunchedEffect(current) {
        val i = cats.indexOfFirst { it.id == current }
        if (i >= 0) rowState.animateScrollToItem(i)
    }
    LazyRow(
        state = rowState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)
    ) {
        items(cats, key = { it.id }) { c ->
            val sel = c.id == current
            Surface(
                onClick = { scope.launch { listState.animateScrollToItem(firstIndex.getValue(c.id)) } },
                shape = RoundedCornerShape(99.dp),
                color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                border = if (sel) null else androidx.compose.foundation.BorderStroke(1.dp, CardLine)
            ) {
                Text(
                    c.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                    color = if (sel) Color.White else InkDark,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                )
            }
        }
    }
}

// Shared with the parcel manager's add-order screen — same one-row-per-dish,
// tap-equals-plus-one picker everywhere an order is punched.
@Composable
fun MenuRow(
    name: String, price: Long, available: Boolean,
    colour: Color, counter: Boolean, inCart: Long,
    onClick: () -> Unit,
    onMinus: (() -> Unit)? = null
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && available) 0.975f else 1f,
        spring(dampingRatio = 0.7f, stiffness = 1100f), label = "menuRow"
    )
    // Last time this row added a plate. The "−" appears under the finger on
    // the first add, so a tap on it right after an add (a fast double or
    // triple tap on the row) still means "one more", not "one less".
    var lastAddAt by remember { mutableLongStateOf(0L) }
    Surface(
        onClick = {
            if (available) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            lastAddAt = SystemClock.uptimeMillis()
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
                // A bare ✋ meant nothing to a new manager: it says "counter"
                // (the waiter hands it over, it never goes to the kitchen).
                counter -> if (pricesVisible()) "${rupees(price)} · ${S.counterTag}" else S.counterTag
                else -> if (pricesVisible()) rupees(price) else ""
            }
            if (sub.isNotEmpty()) Text(
                sub, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
            // A mis-tap is undone right on the row — no hunting in the cart.
            if (inCart > 0 && onMinus != null) {
                Surface(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        if (SystemClock.uptimeMillis() - lastAddAt < 350) {
                            lastAddAt = SystemClock.uptimeMillis(); onClick()
                        } else onMinus()
                    },
                    shape = CircleShape,
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                    modifier = Modifier.padding(end = 8.dp).size(34.dp)
                        .semantics { contentDescription = S.oneLess }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "−", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = InkDark,
                            modifier = Modifier.clearAndSetSemantics {}
                        )
                    }
                }
            }
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

// The cart being punched, docked in the bottom bar right above Send: on screen
// however far down the menu the manager is, so what's about to go to the
// kitchen can be read and fixed without hunting for it. A long cart scrolls
// inside the strip, and a newly added line scrolls into view.
@Composable
fun CartStrip(
    cart: List<CartLine>,
    onNote: (Int) -> Unit,
    onQty: (Int, Long) -> Unit
) {
    val scroll = rememberScrollState()
    var shownLines by remember { mutableIntStateOf(cart.size) }
    LaunchedEffect(cart.size) {
        if (cart.size > shownLines) {
            withFrameNanos { } // let the new line lay out first
            scroll.animateScrollTo(scroll.maxValue)
        }
        shownLines = cart.size
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFFFF3E7),
        border = androidx.compose.foundation.BorderStroke(1.dp, Pb.warnLine),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
    ) {
        Column(Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 2.dp)) {
            Text(
                S.newKot, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = com.pavbhaji.pos.ui.tracking(0.8f), color = WarnAmber
            )
            Column(Modifier.heightIn(max = 168.dp).verticalScroll(scroll)) {
                cart.forEachIndexed { idx, line ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // The dish name gets everything left of the stepper and
                        // wraps instead of turning into "Cheese Butter Ma…" at a
                        // big font. The note lives under it: tap the dish to
                        // write one (the ✎ line says so).
                        Column(
                            Modifier.weight(1f).padding(end = 6.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onNote(idx) }
                                .padding(vertical = 2.dp)
                        ) {
                            Text(
                                line.name, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                maxLines = 3, overflow = TextOverflow.Ellipsis
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (pricesVisible()) Text(
                                    rupees(line.price) + " · ",
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    if (line.note.isEmpty()) "✎ ${S.noteBtn}" else "✎ ${line.note}",
                                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    color = if (line.note.isEmpty()) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                        QtyStepper(line.qty) { q -> onQty(idx, q) }
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
            TextButton(
                onClick = onCancel,
                modifier = Modifier.semantics { contentDescription = S.cancelItem + " · " + it.name }
            ) {
                Text("✕", color = MaterialTheme.colorScheme.error, modifier = Modifier.clearAndSetSemantics {})
            }
        }
    }
}

// Where did they go? Every other dine-in table, four across, small enough
// that the whole room fits. A free one (quiet) takes the order as it is; an
// occupied one (amber, "#63 · join") means joining the two into one bill.
@Composable
private fun MoveTableDialog(
    fromId: Long, fromName: String,
    onPick: (TableStateDto) -> Unit, onDismiss: () -> Unit
) {
    var tables by remember { mutableStateOf<List<TableStateDto>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        when (val r = Deps.api.tables()) {
            is ApiResult.Ok -> tables = r.value.tables.filter { it.kind == "dine" && it.id != fromId }
            is ApiResult.Err -> err = r.message
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(S.moveWhereQ(fromName), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(S.moveHint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                val ts = tables
                when {
                    err != null -> Text(err ?: "", color = MaterialTheme.colorScheme.error)
                    ts == null -> Text(S.loading, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Column(
                        Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ts.chunked(4).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { t ->
                                    MoveTarget(t, Modifier.weight(1f)) { onPick(t) }
                                }
                                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.back) } }
    )
}

@Composable
private fun MoveTarget(t: TableStateDto, modifier: Modifier, onClick: () -> Unit) {
    val o = t.order
    PbCard(
        onClick = onClick,
        corner = 12.dp,
        color = if (o == null) Pb.quietCard else Pb.warnBg,
        border = BorderStroke(1.dp, if (o == null) Color(0xFFEDE5DD) else Pb.warnLine),
        elevation = 0.dp,
        modifier = modifier.heightIn(min = 56.dp)
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
            Text(
                t.name, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                if (o == null) S.freeTable else S.joinTag(o.orderNo),
                fontSize = 11.5.sp, color = Pb.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}
