package com.pavbhaji.pos.ui.inventory

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pavbhaji.pos.Deps
import com.pavbhaji.pos.data.ApiResult
import com.pavbhaji.pos.data.InventoryItemDto
import com.pavbhaji.pos.data.InventoryMoveDto
import com.pavbhaji.pos.data.QTY_MAX_MILLI
import com.pavbhaji.pos.data.cleanQtyInput
import com.pavbhaji.pos.data.fmtQty
import com.pavbhaji.pos.data.orderListText
import com.pavbhaji.pos.data.parseQty
import com.pavbhaji.pos.data.qtyUnit
import com.pavbhaji.pos.ui.AppHeader
import com.pavbhaji.pos.ui.BhajiRed
import com.pavbhaji.pos.ui.BigButton
import com.pavbhaji.pos.ui.CardLine
import com.pavbhaji.pos.ui.ConfirmDialog
import com.pavbhaji.pos.ui.EmptyState
import com.pavbhaji.pos.ui.ErrorBanner
import com.pavbhaji.pos.ui.GoodGreen
import com.pavbhaji.pos.ui.HeaderTextAction
import com.pavbhaji.pos.ui.InkDark
import com.pavbhaji.pos.ui.L10n
import com.pavbhaji.pos.ui.ListSkeleton
import com.pavbhaji.pos.ui.Pb
import com.pavbhaji.pos.ui.PbCard
import com.pavbhaji.pos.ui.Pill
import com.pavbhaji.pos.ui.S
import com.pavbhaji.pos.ui.SectionTitle
import com.pavbhaji.pos.ui.WarnAmber
import com.pavbhaji.pos.ui.clockTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Raw-material stock: what is in hand, and what to order for tomorrow. This
// is NOT the dish-level "khatam" switch (MenuItemDto.available); it never
// touches the menu. Manager and admin only: the server answers 403 to anyone
// else, and no other role is given a way in.
//
// One screen for both: AdminHome opens it from a tile, ManagerHome from the
// row above its tables. Counts are manual (stock in / used / set count);
// nothing is deducted from sales.

// The visible words change with the language; these handles never do. UI
// tests find the controls by them.
object InvTags {
    const val OPEN = "inv-open"             // the manager's row above the tables
    const val ADD = "inv-add"               // header "＋ Add item"
    const val ADD_FIRST = "inv-add-first"   // the big button on the empty list
    const val TAB_ALL = "inv-tab-all"
    const val TAB_ORDER = "inv-tab-order"
    const val SUMMARY = "inv-summary"
    const val ROW = "inv-row-"              // + item id (All stock)
    const val ORDER_ROW = "inv-order-row-"  // + item id (Tomorrow's order)
    const val SHARE = "inv-share"
    const val MINUS = "inv-minus"
    const val PLUS = "inv-plus"
    const val QTY = "inv-qty"
    const val IN = "inv-in"
    const val OUT = "inv-out"
    const val COUNT = "inv-count"
    const val ERROR = "inv-error"           // the red refusal inside a dialog
    const val EDIT = "inv-edit"
    const val HISTORY = "inv-history"
    const val CLOSE = "inv-close"           // a dialog's Back
    const val NAME = "inv-name"
    const val PICK = "inv-pick-"            // + quick-pick name ("Cheese")
    const val UNIT = "inv-unit-"            // + unit ("kg")
    const val NEW_QTY = "inv-new-qty"
    const val PAR = "inv-par"
    const val SAVE = "inv-save"
    const val REMOVE = "inv-remove"
    const val MOVE = "inv-move-"            // + move id (a history line)
}

// Used only until the server's own list has arrived.
private val FallbackUnits = listOf("kg", "g", "L", "ml", "pcs", "packet", "ladi", "dozen", "box", "bottle", "tin")

// One tap fills name and unit. Latin script in every language on purpose:
// the name becomes the item's name, and that is what goes to the supplier.
private val QuickPicks = listOf(
    "Cheese" to "kg", "Paneer" to "kg", "Butter" to "kg", "Pav" to "ladi", "Aloo" to "kg",
    "Tamatar" to "kg", "Kanda" to "kg", "Matar" to "kg", "Shimla mirch" to "kg", "Tel" to "L",
    "Doodh" to "L", "Dahi" to "kg", "Pav bhaji masala" to "kg", "Cold drink" to "bottle"
)

// Which dialog is up for the open item.
private const val PANE_MOVE = "move"
private const val PANE_EDIT = "edit"
private const val PANE_HISTORY = "history"

private fun statusFg(status: String): Color = when (status) {
    "out" -> Pb.danger
    "low" -> WarnAmber
    else -> GoodGreen
}

@Composable
private fun StatusPill(status: String) {
    when (status) {
        "out" -> Pill(S.invOut, Pb.dangerBg, Pb.danger)
        "low" -> Pill(S.invLow, Color(0xFFF5E3C2), WarnAmber)
        else -> Pill(S.invOk, Pb.successBg, GoodGreen)
    }
}

// "12 Oct", month in the reader's language (as the bills history does).
private fun dayLabel(ms: Long): String {
    val loc = when (L10n.lang) {
        "hi" -> java.util.Locale("hi", "IN"); "gu" -> java.util.Locale("gu", "IN"); else -> java.util.Locale("en", "IN")
    }
    return java.text.SimpleDateFormat("d MMM", loc).format(java.util.Date(ms))
}

// The order list as plain text into the Android share sheet (WhatsApp).
private fun shareOrder(context: Context, items: List<InventoryItemDto>) {
    val text = orderListText(S.invOrderTitle, dayLabel(System.currentTimeMillis()), items)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    val chooser = Intent.createChooser(send, S.invShare)
    if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(chooser)
    } catch (e: Exception) {
        Toast.makeText(context, e.message ?: e.toString(), Toast.LENGTH_LONG).show()
    }
}

@Composable
fun InventoryScreen(onBack: () -> Unit) {
    var items by remember { mutableStateOf<List<InventoryItemDto>>(emptyList()) }
    var units by remember { mutableStateOf(FallbackUnits) }
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    // Saved, so an app killed in the background reopens on the same tab and
    // the same item (the half-typed quantity saves itself in the dialog).
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pane by rememberSaveable { mutableStateOf(PANE_MOVE) }
    var addOpen by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    suspend fun refresh() {
        when (val r = Deps.api.inventory()) {
            is ApiResult.Ok -> {
                items = r.value.items
                if (r.value.units.isNotEmpty()) units = r.value.units
                loadError = null
                loaded = true
            }
            is ApiResult.Err -> loadError = r.message
        }
    }
    LaunchedEffect(Unit) {
        refresh()
        Deps.live.topics.collect { if (it == "inventory") refresh() }
    }
    LaunchedEffect(Unit) { // fallback poll in case WiFi ate a push
        while (true) { delay(25_000); refresh() }
    }

    // An own change: the row is right at once from the server's answer, then
    // the whole list is fetched again (order, counts, the other phone's moves).
    fun applied(row: InventoryItemDto) {
        items = when {
            !row.active -> items.filter { it.id != row.id }
            items.any { it.id == row.id } -> items.map { if (it.id == row.id) row else it }
            else -> items + row
        }
        scope.launch { refresh() }
    }
    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    val openAdd = { addOpen = true }
    val openItem = { id: Long -> pane = PANE_MOVE; openId = id }

    val toOrder = items.filter { it.short > 0 }
    Column(Modifier.fillMaxSize()) {
        AppHeader(
            title = S.inventoryTitle,
            subtitle = S.invSubtitle,
            onBack = onBack,
            actions = { Box(Modifier.testTag(InvTags.ADD)) { HeaderTextAction("＋ " + S.invAdd, openAdd) } }
        )
        ErrorBanner(loadError)
        if (!loaded) {
            ListSkeleton(rows = 5)
            return@Column
        }
        if (items.isEmpty()) {
            EmptyState("📦", S.invEmpty)
            BigButton(
                "＋ " + S.invAdd, openAdd,
                modifier = Modifier.padding(horizontal = 24.dp).testTag(InvTags.ADD_FIRST)
            )
            return@Column
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 12.dp)
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Segment(S.invAll, tab == 0, Modifier.weight(1f).fillMaxHeight().testTag(InvTags.TAB_ALL)) { tab = 0 }
            Segment(
                S.invOrderTab(toOrder.size), tab == 1,
                Modifier.weight(1f).fillMaxHeight().testTag(InvTags.TAB_ORDER)
            ) { tab = 1 }
        }
        val lowN = items.count { it.status == "low" }
        val outN = items.count { it.status == "out" }
        Text(
            listOfNotNull(
                S.invItemsN(items.size),
                if (lowN > 0) S.invLowN(lowN) else null,
                if (outN > 0) S.invOutN(outN) else null,
                if (lowN == 0 && outN == 0) S.invAllFull else null
            ).joinToString(" · "),
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Pb.ink2,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag(InvTags.SUMMARY)
        )

        if (tab == 0) {
            LazyColumn(
                Modifier.weight(1f).navigationBarsPadding(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(items, key = { it.id }) { item -> StockRow(item) { openItem(item.id) } }
            }
        } else if (toOrder.isEmpty()) {
            EmptyState("✅", S.invAllFull)
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(toOrder, key = { it.id }) { item -> OrderRow(item) { openItem(item.id) } }
            }
            Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
                    BigButton(
                        S.invShare,
                        onClick = {
                            if (toOrder.isEmpty()) toast(S.invShareNothing) else shareOrder(context, toOrder)
                        },
                        modifier = Modifier.testTag(InvTags.SHARE)
                    )
                }
            }
        }
    }

    // Dialogs sit outside the column: Add must also work on the empty list.
    val open = items.firstOrNull { it.id == openId }
    // Someone else removed the item this phone had open.
    LaunchedEffect(loaded, openId, open == null) { if (loaded && openId != null && open == null) openId = null }
    if (open != null) {
        when (pane) {
            PANE_EDIT -> ItemFormDialog(
                item = open, units = units, taken = emptyList(),
                onSaved = { applied(it); pane = PANE_MOVE },
                onRemoved = { openId = null; pane = PANE_MOVE; applied(it); toast(S.invRemoved(it.name)) },
                onDismiss = { pane = PANE_MOVE }
            )
            PANE_HISTORY -> HistoryDialog(open, onDismiss = { pane = PANE_MOVE })
            else -> MoveDialog(
                item = open,
                onDone = { openId = null; applied(it); toast(it.name + ": " + qtyUnit(it.qty, it.unit)) },
                onEdit = { pane = PANE_EDIT },
                onHistory = { pane = PANE_HISTORY },
                onDismiss = { openId = null }
            )
        }
    }
    if (addOpen) {
        ItemFormDialog(
            item = null, units = units, taken = items.map { it.name },
            onSaved = { addOpen = false; applied(it); toast(it.name + ": " + qtyUnit(it.qty, it.unit)) },
            onRemoved = {},
            onDismiss = { addOpen = false }
        )
    }
}

// What the manager's door on the Tables screen says before it is opened.
class InventoryGlance(val items: Int, val out: Int, val low: Int)

// null until the first answer, and for as long as the server cannot be
// reached: the door opens either way, it just says less.
@Composable
fun rememberInventoryGlance(): InventoryGlance? {
    var glance by remember { mutableStateOf<InventoryGlance?>(null) }
    LaunchedEffect(Unit) {
        suspend fun load() {
            val r = Deps.api.inventory()
            if (r is ApiResult.Ok) {
                val items = r.value.items
                glance = InventoryGlance(items.size, items.count { it.status == "out" }, items.count { it.status == "low" })
            }
        }
        load()
        Deps.live.topics.collect { if (it == "inventory") load() }
    }
    return glance
}

// The manager's door: one row above the tables that already answers "is
// anything short?", so the common case needs no tap. Red when something has
// run out, amber when something is low, green when every level is met.
@Composable
fun InventoryEntry(glance: InventoryGlance?, onClick: () -> Unit) {
    val line: String
    val fg: Color
    when {
        glance == null || glance.items == 0 -> { line = S.invEntryHint; fg = Pb.ink2 }
        glance.out > 0 -> {
            line = listOfNotNull(S.invOutN(glance.out), if (glance.low > 0) S.invLowN(glance.low) else null)
                .joinToString(" · ")
            fg = Pb.danger
        }
        glance.low > 0 -> { line = S.invLowN(glance.low); fg = WarnAmber }
        else -> { line = S.invAllFull; fg = GoodGreen }
    }
    PbCard(onClick = onClick, corner = 14.dp, modifier = Modifier.fillMaxWidth().testTag(InvTags.OPEN)) {
        Row(
            Modifier.heightIn(min = 56.dp).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("📦", fontSize = 22.sp, modifier = Modifier.clearAndSetSemantics {})
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                Text(S.inventoryTitle, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = InkDark)
                Text(line, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = fg, lineHeight = 17.sp)
            }
            Text(
                "›", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Pb.ink3,
                modifier = Modifier.clearAndSetSemantics {}
            )
        }
    }
}

// One of the two tabs. Both are as tall as the taller one, so a label that
// wraps at a big font ("Tomorrow's order (3)") does not leave them uneven.
@Composable
private fun Segment(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        selected = selected, onClick = onClick,
        color = if (selected) BhajiRed else MaterialTheme.colorScheme.surface,
        border = if (selected) null else BorderStroke(1.dp, CardLine),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier
    ) {
        Box(
            Modifier.heightIn(min = 48.dp).padding(horizontal = 8.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label, color = if (selected) Color.White else InkDark,
                fontSize = 14.sp, fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center, lineHeight = 18.sp
            )
        }
    }
}

// All stock: the name and the daily need on the left; on the right what is in
// hand, large and in the status colour, its pill, and how much to order.
@Composable
private fun StockRow(item: InventoryItemDto, onClick: () -> Unit) {
    val fg = statusFg(item.status)
    PbCard(onClick = onClick, corner = 14.dp, modifier = Modifier.fillMaxWidth().testTag(InvTags.ROW + item.id)) {
        Row(
            Modifier.heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(item.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = InkDark, lineHeight = 21.sp)
                if (item.par > 0) {
                    Text(S.invNeed(qtyUnit(item.par, item.unit)), fontSize = 13.sp, color = Pb.ink2)
                }
            }
            Column(Modifier.widthIn(max = 176.dp), horizontalAlignment = Alignment.End) {
                Text(
                    qtyUnit(item.qty, item.unit), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                    color = fg, textAlign = TextAlign.End
                )
                StatusPill(item.status)
                if (item.short > 0) {
                    Text(
                        S.invOrderQ(qtyUnit(item.short, item.unit)), fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold, color = fg, textAlign = TextAlign.End,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

// Tomorrow's order: the amount to buy is the big number, what is in hand now
// is the small print.
@Composable
private fun OrderRow(item: InventoryItemDto, onClick: () -> Unit) {
    PbCard(onClick = onClick, corner = 14.dp, modifier = Modifier.fillMaxWidth().testTag(InvTags.ORDER_ROW + item.id)) {
        Row(
            Modifier.heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(item.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = InkDark, lineHeight = 21.sp)
                Text(S.invNow(qtyUnit(item.qty, item.unit)), fontSize = 13.sp, color = Pb.ink2)
            }
            Text(
                qtyUnit(item.short, item.unit), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                color = BhajiRed, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 176.dp)
            )
        }
    }
}

// − and + around the quantity field: 56dp, for a thumb at the counter.
@Composable
private fun StepButton(glyph: String, describe: String, tag: String, onClick: () -> Unit) {
    val view = LocalView.current
    OutlinedButton(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        },
        modifier = Modifier.size(56.dp).testTag(tag).semantics { contentDescription = describe },
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.5.dp, CardLine),
        contentPadding = PaddingValues(0.dp)
    ) { Text(glyph, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = InkDark) }
}

// The item's dialog: how much, then one of the three moves. The buttons stay
// enabled and check on the tap: an empty field is not zero, so a stray tap
// on "Set count" can never zero the stock. A refusal (more used than there
// is) shows in red here and the dialog stays open.
@Composable
private fun MoveDialog(
    item: InventoryItemDto,
    onDone: (InventoryItemDto) -> Unit,
    onEdit: () -> Unit,
    onHistory: () -> Unit,
    onDismiss: () -> Unit
) {
    var text by rememberSaveable(item.id) { mutableStateOf("") }
    var dErr by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) } // the move on its way
    val scope = rememberCoroutineScope()

    fun step(by: Long) {
        val cur = parseQty(text)
        if (cur == null && by < 0) return // nothing typed: there is nothing to take one off
        text = fmtQty(((cur ?: 0L) + by).coerceIn(0L, QTY_MAX_MILLI))
        dErr = null
    }
    fun send(kind: String) {
        if (busy != null) return
        val q = parseQty(text)
        if (q == null || (q == 0L && kind != "count")) { dErr = S.invQtyFirst; return }
        busy = kind
        dErr = null
        scope.launch {
            when (val r = Deps.api.inventoryMove(item.id, kind, q)) {
                is ApiResult.Ok -> onDone(r.value)
                is ApiResult.Err -> dErr = r.message
            }
            busy = null
        }
    }

    AlertDialog(
        onDismissRequest = { if (busy == null) onDismiss() },
        shape = RoundedCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Column {
                Text(item.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp)
                Box(Modifier.padding(top = 6.dp)) { StatusPill(item.status) }
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    S.invInHand(qtyUnit(item.qty, item.unit)), fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, color = InkDark
                )
                if (item.par > 0) {
                    Text(S.invNeed(qtyUnit(item.par, item.unit)), fontSize = 13.sp, color = Pb.ink2)
                }
                if (item.receivedToday > 0 || item.usedToday > 0) {
                    Text(
                        S.invToday(qtyUnit(item.receivedToday, item.unit), qtyUnit(item.usedToday, item.unit)),
                        fontSize = 13.sp, color = Pb.ink2
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StepButton("−", S.invLess, InvTags.MINUS) { step(-1000) }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = cleanQtyInput(it); dErr = null },
                        modifier = Modifier.weight(1f).testTag(InvTags.QTY),
                        label = { Text(S.invQty) },
                        suffix = { Text(item.unit) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        textStyle = LocalTextStyle.current.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    )
                    StepButton("+", S.invMore, InvTags.PLUS) { step(1000) }
                }
                dErr?.let {
                    Text(
                        it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 8.dp).testTag(InvTags.ERROR)
                    )
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 12.dp).height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BigButton(
                        "＋ " + S.invStockIn, { send("in") },
                        modifier = Modifier.weight(1f).fillMaxHeight().testTag(InvTags.IN),
                        bg = GoodGreen,
                        enabled = busy == null || busy == "in", loading = busy == "in", loadingLabel = "…"
                    )
                    BigButton(
                        "− " + S.invUsed, { send("out") },
                        modifier = Modifier.weight(1f).fillMaxHeight().testTag(InvTags.OUT),
                        bg = Pb.danger,
                        enabled = busy == null || busy == "out", loading = busy == "out", loadingLabel = "…"
                    )
                }
                BigButton(
                    "= " + S.invSetCount, { send("count") },
                    modifier = Modifier.padding(top = 8.dp).testTag(InvTags.COUNT),
                    bg = MaterialTheme.colorScheme.surface, fg = InkDark,
                    border = BorderStroke(1.5.dp, InkDark),
                    enabled = busy == null || busy == "count", loading = busy == "count", loadingLabel = "…"
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (busy == null) onDismiss() }, modifier = Modifier.testTag(InvTags.CLOSE)) {
                Text(S.back)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onEdit, modifier = Modifier.testTag(InvTags.EDIT)) { Text(S.invEdit) }
                TextButton(onClick = onHistory, modifier = Modifier.testTag(InvTags.HISTORY)) { Text(S.invHistory) }
            }
        }
    )
}

// Add (item == null) and Edit share this form. Add also takes what is in hand
// now and offers the quick picks; Edit sends only what changed and can remove
// the item from the list (its history stays on the server).
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemFormDialog(
    item: InventoryItemDto?,
    units: List<String>,
    taken: List<String>,
    onSaved: (InventoryItemDto) -> Unit,
    onRemoved: (InventoryItemDto) -> Unit,
    onDismiss: () -> Unit
) {
    var name by rememberSaveable(item?.id) { mutableStateOf(item?.name ?: "") }
    var unit by rememberSaveable(item?.id) { mutableStateOf(item?.unit ?: "kg") }
    var qtyText by rememberSaveable(item?.id) { mutableStateOf("") }
    var parText by rememberSaveable(item?.id) {
        mutableStateOf(if (item != null && item.par > 0) fmtQty(item.par) else "")
    }
    var dErr by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var askRemove by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun save() {
        if (busy) return
        val n = name.trim()
        if (n.isEmpty()) { dErr = S.invNameNeeded; return }
        // An empty quantity is "none yet"; anything typed has to be a number.
        val qty = if (qtyText.isBlank()) 0L else parseQty(qtyText)
        val par = if (parText.isBlank()) 0L else parseQty(parText)
        if (qty == null || par == null) { dErr = S.invQtyFirst; return }
        val patch = if (item == null) null else buildJsonObject {
            if (n != item.name) put("name", n)
            if (unit != item.unit) put("unit", unit)
            if (par != item.par) put("par", par)
        }
        if (patch != null && patch.isEmpty()) { onDismiss(); return } // nothing changed
        busy = true
        dErr = null
        scope.launch {
            val r = if (item == null || patch == null) Deps.api.inventoryCreate(n, unit, qty, par)
            else Deps.api.inventoryPatch(item.id, patch.toString())
            when (r) {
                is ApiResult.Ok -> onSaved(r.value)
                is ApiResult.Err -> dErr = r.message
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        shape = RoundedCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(if (item == null) S.invAdd else S.invEdit, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40); dErr = null },
                    label = { Text(S.invNameEg) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth().testTag(InvTags.NAME)
                )
                if (item == null) {
                    val picks = QuickPicks.filter { p -> taken.none { it.trim().equals(p.first, ignoreCase = true) } }
                    if (picks.isNotEmpty()) {
                        SectionTitle(S.invQuick)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            picks.forEach { (pickName, pickUnit) ->
                                FilterChip(
                                    selected = name == pickName,
                                    onClick = { name = pickName; unit = pickUnit; dErr = null },
                                    label = { Text(pickName) },
                                    modifier = Modifier.testTag(InvTags.PICK + pickName)
                                )
                            }
                        }
                    }
                }
                SectionTitle(S.invUnit)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // An item keeps a unit that is no longer on the server's list.
                    (units + unit).distinct().forEach { u ->
                        FilterChip(
                            selected = unit == u,
                            onClick = { unit = u },
                            label = { Text(u) },
                            modifier = Modifier.testTag(InvTags.UNIT + u)
                        )
                    }
                }
                if (item == null) {
                    OutlinedTextField(
                        value = qtyText,
                        onValueChange = { qtyText = cleanQtyInput(it); dErr = null },
                        label = { Text(S.invQtyNow) },
                        suffix = { Text(unit) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag(InvTags.NEW_QTY)
                    )
                }
                OutlinedTextField(
                    value = parText,
                    onValueChange = { parText = cleanQtyInput(it); dErr = null },
                    label = { Text(S.invPar) },
                    suffix = { Text(unit) },
                    supportingText = { Text(S.invParHint) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).testTag(InvTags.PAR)
                )
                dErr?.let {
                    Text(
                        it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp).testTag(InvTags.ERROR)
                    )
                }
                if (item != null) {
                    TextButton(
                        onClick = { if (!busy) askRemove = true },
                        modifier = Modifier.padding(top = 4.dp).testTag(InvTags.REMOVE)
                    ) { Text(S.invRemove, color = Pb.danger, fontWeight = FontWeight.Bold) }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { save() }, enabled = !busy,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.testTag(InvTags.SAVE)
            ) { Text(if (item == null) S.add else S.save, fontWeight = FontWeight.Bold) }
        },
        dismissButton = {
            TextButton(onClick = { if (!busy) onDismiss() }, modifier = Modifier.testTag(InvTags.CLOSE)) {
                Text(S.back)
            }
        }
    )

    if (askRemove && item != null) {
        ConfirmDialog(
            title = S.invRemoveQ(item.name),
            body = S.invRemoveBody,
            confirmLabel = S.invRemoveBtn,
            destructive = true,
            onDismiss = { askRemove = false },
            onConfirm = {
                askRemove = false
                if (!busy) {
                    busy = true
                    dErr = null
                    scope.launch {
                        when (val r = Deps.api.inventoryPatch(item.id, """{"active":false}""")) {
                            is ApiResult.Ok -> onRemoved(r.value)
                            is ApiResult.Err -> dErr = r.message
                        }
                        busy = false
                    }
                }
            }
        )
    }
}

// The last 30 moves of one item, newest first: what happened and what was in
// hand after it, then the day, the time and who wrote it.
@Composable
private fun HistoryDialog(item: InventoryItemDto, onDismiss: () -> Unit) {
    var moves by remember { mutableStateOf<List<InventoryMoveDto>?>(null) }
    var dErr by remember { mutableStateOf<String?>(null) }
    // Keyed on updatedAt too: a move made on another phone shows up here.
    LaunchedEffect(item.id, item.updatedAt) {
        when (val r = Deps.api.inventoryMoves(item.id, 30)) {
            is ApiResult.Ok -> { moves = r.value.moves; dErr = null }
            is ApiResult.Err -> dErr = r.message
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Column {
                Text(item.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp)
                Text(S.invHistory, fontSize = 13.sp, color = Pb.ink2)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                dErr?.let {
                    Text(
                        it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                        modifier = Modifier.testTag(InvTags.ERROR)
                    )
                }
                val list = moves
                when {
                    list == null -> if (dErr == null) Text(S.loading, fontSize = 14.sp, color = Pb.ink2)
                    list.isEmpty() -> Text(S.invNoHistory, fontSize = 14.sp, color = Pb.ink2)
                    else -> list.forEachIndexed { i, m ->
                        if (i > 0) HorizontalDivider(color = CardLine)
                        MoveLine(m, item.unit)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(InvTags.CLOSE)) { Text(S.back) }
        }
    )
}

@Composable
private fun MoveLine(m: InventoryMoveDto, unit: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag(InvTags.MOVE + m.id)) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                when (m.kind) {
                    "in" -> S.invMoveIn(qtyUnit(m.delta, unit))
                    "out" -> S.invMoveOut(qtyUnit(-m.delta, unit))
                    "count" -> S.invMoveCount(qtyUnit(m.qtyAfter, unit))
                    // A kind a newer server writes: the plain signed change.
                    else -> (if (m.delta < 0) "−" else "+") + qtyUnit(kotlin.math.abs(m.delta), unit)
                },
                fontSize = 15.sp, fontWeight = FontWeight.Bold,
                color = when (m.kind) { "in" -> GoodGreen; "out" -> Pb.danger; else -> InkDark },
                modifier = Modifier.weight(1f)
            )
            // A count already says what is in hand; in and out add it.
            if (m.kind != "count") {
                Text(
                    "→ " + qtyUnit(m.qtyAfter, unit), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = InkDark, textAlign = TextAlign.End, modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        Text(
            listOf(dayLabel(m.at), clockTime(m.at), m.by, m.note).filter { it.isNotBlank() }.joinToString(" · "),
            fontSize = 12.sp, color = Pb.ink3, modifier = Modifier.padding(top = 2.dp)
        )
    }
}
