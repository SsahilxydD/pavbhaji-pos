package com.pavbhaji.pos.ui.cashier

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pavbhaji.pos.Deps
import com.pavbhaji.pos.data.ApiResult
import com.pavbhaji.pos.data.BillDto
import com.pavbhaji.pos.data.BillSummaryDto
import com.pavbhaji.pos.data.OrderDetailDto
import com.pavbhaji.pos.data.TableStateDto
import com.pavbhaji.pos.ui.AppHeader
import com.pavbhaji.pos.ui.BhajiRed
import com.pavbhaji.pos.ui.BigButton
import com.pavbhaji.pos.ui.CardLine
import com.pavbhaji.pos.ui.ConfirmDialog
import com.pavbhaji.pos.ui.ErrorBanner
import com.pavbhaji.pos.ui.GoodGreen
import com.pavbhaji.pos.ui.Pb
import com.pavbhaji.pos.ui.S
import com.pavbhaji.pos.ui.WarnAmber
import com.pavbhaji.pos.ui.clockTime
import com.pavbhaji.pos.ui.rupees
import com.pavbhaji.pos.ui.waiter.TablesScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

// Cashier's home: two tabs. Tables = the familiar grid (with ₹ totals — the
// cashier is a money role); tapping an occupied table opens its BILL, not the
// punch screen. Bills = the settled-bill history: nothing vanishes on close —
// every bill can be reopened, read in full and reprinted. Cashiers never
// punch orders; they settle them, and closing is theirs alone.
@Composable
fun CashierHome(onLogout: () -> Unit) {
    var billFor by remember { mutableStateOf<TableStateDto?>(null) }
    var tab by remember { mutableIntStateOf(0) }

    val sel = billFor
    if (sel != null) {
        BillScreen(tableId = sel.id, tableName = sel.name, onBack = { billFor = null })
        return
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f)) {
            if (tab == 0) {
                TablesScreen(
                    onOpenTable = { if (it.order != null) billFor = it },
                    onLogout = onLogout,
                    onBack = null
                )
            } else {
                BillsHistoryScreen(onLogout = onLogout, onBack = null)
            }
        }
        NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
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
                icon = { Text("🧾", fontSize = 17.sp) },
                label = { Text(S.navBills, fontSize = 11.sp, fontWeight = FontWeight.Bold) },
                colors = NavigationBarItemDefaults.colors(
                    selectedTextColor = BhajiRed, indicatorColor = Color(0xFFFFE0CC)
                )
            )
        }
    }
}

// One printed-bill row: identical dishes merged across KOTs, cancelled lines
// dropped — what the customer pays for, nothing else.
private data class BillLine(val name: String, val qty: Long, val rate: Long, val amt: Long)

private fun billLines(o: OrderDetailDto): List<BillLine> {
    val out = LinkedHashMap<String, BillLine>()
    for (tk in o.tickets) for (it in tk.items) {
        if (it.status == "cancelled") continue
        val key = "${it.name} ${it.price}"
        val prev = out[key]
        out[key] = if (prev == null) BillLine(it.name, it.qty, it.price, it.qty * it.price)
        else prev.copy(qty = prev.qty + it.qty, amt = prev.amt + it.qty * it.price)
    }
    return out.values.toList()
}

// "120" / "120.50" / "₹120" → paise, or null when unparseable/negative.
private fun toPaise(s: String): Long? {
    val v = s.trim().removePrefix("₹").trim().toDoubleOrNull() ?: return null
    if (v < 0 || v > 10_000_000) return null
    return Math.round(v * 100)
}

// The PATCH body for "save discount". Money already recorded (💵 all cash,
// then ＋ discount) would be more than the new total, and the server refuses
// that with "payment exceeds the bill total" — the cashier was stuck. So the
// recorded split shrinks in the same PATCH, exactly as the web cashier does:
// online kept first, the rest cash. It only ever shrinks, so no collection is
// invented. `subtotal` is the smaller of the bill's and the live order's, in
// case either copy is a beat behind.
internal fun discountPatch(b: BillDto, subtotal: Long, discount: Long, note: String): String {
    val noteJson = JsonPrimitive(note).toString()
    val newTotal = subtotal - discount
    if (b.paidCash + b.paidOnline <= newTotal) {
        return """{"discount":$discount,"discountNote":$noteJson}"""
    }
    val online = minOf(b.paidOnline, maxOf(0L, newTotal))
    val cash = minOf(b.paidCash, maxOf(0L, newTotal - online))
    return """{"discount":$discount,"discountNote":$noteJson,"paidCash":$cash,"paidOnline":$online}"""
}

@Composable
fun BillScreen(tableId: Long, tableName: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var order by remember { mutableStateOf<OrderDetailDto?>(null) }
    var bill by remember { mutableStateOf<BillDto?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    var blockers by remember { mutableStateOf<List<String>?>(null) }
    var discountOpen by remember { mutableStateOf(false) }
    var onlineTxt by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }
    // The WebView must outlive the click that made it: Android renders the
    // print job from it asynchronously after the dialog opens.
    var printHold by remember { mutableStateOf<WebView?>(null) }

    // Every refresh re-generates the bill server-side, so its subtotal always
    // tracks the live items (a KOT punched mid-billing can't sneak past).
    var loadError by remember { mutableStateOf<String?>(null) }
    suspend fun refresh() {
        when (val r = Deps.api.tableOrder(tableId)) {
            is ApiResult.Ok -> {
                if (r.value.order == null && order != null) { onBack(); return }
                order = r.value.order
                loadError = null
            }
            is ApiResult.Err -> loadError = r.message
        }
        if (order != null) {
            when (val b = Deps.api.billGenerate(order!!.id)) {
                is ApiResult.Ok -> { bill = b.value; loadError = null }
                is ApiResult.Err -> loadError = b.message
            }
        }
    }
    LaunchedEffect(Unit) {
        refresh()
        Deps.live.topics.collect { if (it == "tables" || it == "kitchen") refresh() }
    }
    LaunchedEffect(Unit) { while (true) { delay(25_000); refresh() } }

    fun patchBill(patch: String) {
        val o = order ?: return
        scope.launch {
            when (val r = Deps.api.billPatch(o.id, patch)) {
                is ApiResult.Ok -> bill = r.value
                // Refused: say why (already in her language — serverErrText),
                // and re-read the bill so the screen shows what the server holds.
                is ApiResult.Err -> { toast = r.message; refresh() }
            }
        }
    }

    fun setPayment(cash: Long, online: Long) = patchBill("""{"paidCash":$cash,"paidOnline":$online}""")

    fun print(o: OrderDetailDto) {
        val wv = WebView(context)
        var fired = false
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (fired) return
                fired = true
                val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val job = "PavBhaji bill #${o.orderNo}"
                pm.print(job, view.createPrintDocumentAdapter(job), PrintAttributes.Builder().build())
            }
        }
        printHold = wv
        wv.loadUrl(Deps.api.billUrl(o.id))
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
        val o = order
        val b = bill
        AppHeader(
            title = "${S.billTitle} · $tableName",
            subtitle = o?.let { "${S.orderNo(it.orderNo)} · ${clockTime(it.openedAt)} · ${it.openedByName}" },
            onBack = onBack
        )
        ErrorBanner(loadError)
        toast?.let {
            Text(
                it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp)
            )
            LaunchedEffect(it) { delay(3000); toast = null }
        }

        if (o == null) {
            Spacer(Modifier.weight(1f))
        } else {
            val lines = billLines(o)
            val pending = o.tickets.sumOf { t -> t.items.filter { it.status == "pending" || it.status == "preparing" }.sumOf { it.qty } }
            val ready = o.tickets.sumOf { t -> t.items.filter { it.status == "ready" }.sumOf { it.qty } }
            val total = b?.total ?: o.total
            val paid = (b?.paidCash ?: 0) + (b?.paidOnline ?: 0)
            val settled = b != null && paid == total && b.subtotal == o.total

            LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                item {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, CardLine),
                        shadowElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            // who the bill belongs to
                            if (o.customerName.isNotEmpty()) {
                                Text(o.customerName, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                                val sub = listOfNotNull(
                                    o.customerPhone.ifEmpty { null }, tableName
                                ).joinToString(" · ")
                                Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(tableName, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            HorizontalDivider(Modifier.padding(vertical = 10.dp))

                            // column headers, invoice style
                            Row {
                                Text(
                                    S.billItem.uppercase(), fontSize = 10.sp, letterSpacing = 0.8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                BillCol(S.billQty.uppercase(), 42.dp, muted = true)
                                BillCol(S.billRate.uppercase(), 64.dp, muted = true)
                                BillCol(S.billAmount.uppercase(), 76.dp, muted = true)
                            }
                            lines.forEach { l ->
                                Row(Modifier.padding(vertical = 4.dp)) {
                                    Text(l.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    BillCol("${l.qty}", 42.dp)
                                    BillCol(rupees(l.rate), 64.dp)
                                    BillCol(rupees(l.amt), 76.dp, bold = true)
                                }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 10.dp))

                            // ---- subtotal → discount → total ----
                            if (b != null && b.discount > 0) {
                                Row(Modifier.padding(bottom = 3.dp)) {
                                    Text(S.subtotalRow, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    Text(rupees(b.subtotal), fontSize = 13.sp)
                                }
                                Row(
                                    Modifier.padding(bottom = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        S.discountRow + (if (b.discountNote.isNotEmpty()) " · ${b.discountNote}" else ""),
                                        fontSize = 13.sp, color = WarnAmber, modifier = Modifier.weight(1f)
                                    )
                                    Text("− ${rupees(b.discount)}", fontSize = 13.sp, color = WarnAmber, fontWeight = FontWeight.Bold)
                                    TextButton(
                                        onClick = { discountOpen = true },
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp)
                                    ) { Text("✎", fontSize = 13.sp) }
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    S.totalRow, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(rupees(total), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (b != null && b.discount == 0L) {
                                    TextButton(
                                        onClick = { discountOpen = true },
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                                    ) { Text(S.addDiscount, fontSize = 12.sp) }
                                }
                                Text(
                                    S.itemsN(lines.sumOf { it.qty }),
                                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.End, modifier = Modifier.weight(1f)
                                )
                            }
                            if (pending > 0 || ready > 0) {
                                Spacer(Modifier.height(6.dp))
                                Text(S.billStatusLine(pending, ready), fontSize = 12.5.sp, color = WarnAmber, fontWeight = FontWeight.SemiBold)
                                Text(S.billRunning, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }

                            // ---- payment: all cash, all online, or any split ----
                            HorizontalDivider(Modifier.padding(vertical = 10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    S.paymentSection, fontSize = 10.sp, letterSpacing = 0.8.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                if (b != null) {
                                    if (settled) {
                                        Text(S.paidTick, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = GoodGreen)
                                    } else {
                                        Text(S.payLeft(rupees(total - paid)), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = WarnAmber)
                                    }
                                }
                            }
                            if (b != null) {
                                Row(Modifier.padding(top = 6.dp)) {
                                    Text("💵 ${S.cashLbl}", fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                                    Text(rupees(b.paidCash), fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                                }
                                Row(Modifier.padding(top = 2.dp)) {
                                    Text("📲 ${S.onlineLbl}", fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                                    Text(rupees(b.paidOnline), fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                                }
                                Row(
                                    Modifier.padding(top = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = { setPayment(total, 0) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                                    ) { Text(S.allCashChip, fontSize = 12.5.sp, fontWeight = FontWeight.Bold) }
                                    OutlinedButton(
                                        onClick = { setPayment(0, total) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                                    ) { Text(S.allOnlineChip, fontSize = 12.5.sp, fontWeight = FontWeight.Bold) }
                                }
                                Row(
                                    Modifier.padding(top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    OutlinedTextField(
                                        value = onlineTxt,
                                        onValueChange = { onlineTxt = it.filter { c -> c.isDigit() || c == '.' }.take(9) },
                                        label = { Text(S.onlineAmtLbl, fontSize = 12.sp) },
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = {
                                            val p = toPaise(onlineTxt)
                                            if (p == null || p > total) { toast = S.payLeft(rupees(total)) }
                                            else { setPayment(total - p, p); onlineTxt = "" }
                                        },
                                        shape = RoundedCornerShape(10.dp)
                                    ) { Text(S.splitApply, fontSize = 12.5.sp, fontWeight = FontWeight.Bold) }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            // ---- bottom actions ----
            Surface(shadowElevation = 10.dp) {
                Column(
                    Modifier.fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BigButton(
                        label = S.printBill,
                        onClick = { print(o) },
                        fontSize = 16
                    )
                    BigButton(
                        label = S.closeAmt(rupees(total)),
                        onClick = { confirmClose = true },
                        enabled = settled,
                        bg = Pb.successDeep,
                        fontSize = 14
                    )
                }
            }
        }
    }

    if (discountOpen) {
        val b = bill
        var amtTxt by remember { mutableStateOf(if (b != null && b.discount > 0) (b.discount / 100.0).let { if (it % 1.0 == 0.0) "${it.toLong()}" else "$it" } else "") }
        var noteTxt by remember { mutableStateOf(b?.discountNote ?: "") }
        var amtErr by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { discountOpen = false },
            title = { Text(S.discountRow) },
            text = {
                Column {
                    OutlinedTextField(
                        value = amtTxt,
                        onValueChange = { amtTxt = it.filter { c -> c.isDigit() || c == '.' }.take(9); amtErr = null },
                        label = { Text(S.discountAmt) },
                        singleLine = true,
                        isError = amtErr != null,
                        supportingText = amtErr?.let { e -> { Text(e) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = noteTxt,
                        onValueChange = { noteTxt = it.take(120) },
                        label = { Text(S.discountNoteLbl) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val d = if (amtTxt.isBlank()) 0L else toPaise(amtTxt)
                    val cur = bill
                    if (d == null || cur == null) return@Button
                    val sub = order?.let { minOf(cur.subtotal, it.total) } ?: cur.subtotal
                    if (d > sub) { amtErr = S.discountTooBig; return@Button }
                    patchBill(discountPatch(cur, sub, d, noteTxt.trim()))
                    discountOpen = false
                }) { Text(S.save) }
            },
            dismissButton = { TextButton(onClick = { discountOpen = false }) { Text(S.back) } }
        )
    }
    if (confirmClose) {
        // Unserved plates no longer block the close — the cashier just sees
        // exactly what is going out unserved before they tap it.
        val left = (order?.tickets ?: emptyList()).flatMap { it.items }
            .filter { it.status != "served" && it.status != "cancelled" }
            .map { "• ${it.qty}× ${it.name}" }
        ConfirmDialog(
            title = S.closeQ(tableName),
            body = S.totalLine(rupees(bill?.total ?: order?.total ?: 0)) + "\n" + S.collectFirst +
                if (left.isEmpty()) ""
                else "\n\n" + S.notServedYet + "\n" + left.joinToString("\n") + "\n" + S.closeAnywayHint,
            confirmLabel = if (left.isEmpty()) S.closeTable else S.closeAnyway,
            onConfirm = { confirmClose = false; closeNow() },
            onDismiss = { confirmClose = false }
        )
    }
    blockers?.let { b ->
        AlertDialog(
            onDismissRequest = { blockers = null },
            title = { Text(S.cantClose) },
            text = {
                Column {
                    b.forEach { Text("• $it", fontSize = 14.sp) }
                    Text(S.cantCloseHint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { Button(onClick = { blockers = null }) { Text(S.back) } }
        )
    }
}

private fun shiftDate(date: String, days: Int): String = try {
    val p = date.split("-").map { it.toInt() }
    val cal = java.util.Calendar.getInstance()
    cal.set(p[0], p[1] - 1, p[2])
    cal.add(java.util.Calendar.DAY_OF_MONTH, days)
    "%04d-%02d-%02d".format(
        cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1,
        cal.get(java.util.Calendar.DAY_OF_MONTH)
    )
} catch (e: Exception) { date }

// The settled-bill history: one business day's closed bills, newest first.
// Tap a bill for the full read-back (items, discount, payment) + reprint.
// Shared by the cashier's Bills tab and the admin's Bills door.
@Composable
fun BillsHistoryScreen(onLogout: (() -> Unit)?, onBack: (() -> Unit)?) {
    var date by remember { mutableStateOf<String?>(null) } // null = today
    var resp by remember { mutableStateOf(com.pavbhaji.pos.data.BillsResp()) }
    var openBill by remember { mutableStateOf<BillSummaryDto?>(null) }

    var loadError by remember { mutableStateOf<String?>(null) }
    suspend fun refresh() {
        when (val r = Deps.api.bills(date)) {
            is ApiResult.Ok -> { resp = r.value; loadError = null }
            is ApiResult.Err -> loadError = r.message
        }
    }
    LaunchedEffect(date) { refresh() }
    LaunchedEffect(Unit) {
        Deps.live.topics.collect { if (it == "tables") refresh() } // a close lands here live
    }
    LaunchedEffect(Unit) { while (true) { delay(30_000); refresh() } }

    val sel = openBill
    if (sel != null) {
        BillHistoryDetail(sel, onBack = { openBill = null })
        return
    }

    Column(Modifier.fillMaxSize()) {
        AppHeader(
            title = "🧾 " + S.navBills,
            subtitle = "${S.billsCount(resp.bills.size)} · ${rupees(resp.bills.sumOf { it.total })}",
            onBack = onBack,
            actions = { if (onBack == null && onLogout != null) com.pavbhaji.pos.ui.HeaderTextAction(S.logout, onLogout) }
        )
        ErrorBanner(loadError)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            TextButton(onClick = { date = shiftDate(resp.date, -1) }) { Text("◀") }
            Text(resp.date, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { date = shiftDate(resp.date, +1) }) { Text("▶") }
            TextButton(onClick = { date = null }) { Text(S.todayBtn, fontSize = 12.sp) }
        }
        if (resp.bills.isEmpty()) {
            com.pavbhaji.pos.ui.EmptyHint(S.noBillsDay)
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            resp.bills.forEach { b ->
                item(key = b.orderId) { BillHistRow(b, onClick = { openBill = b }) }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun BillHistRow(b: BillSummaryDto, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, CardLine),
        shadowElevation = 1.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("#${b.orderNo}", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        b.customerName.ifEmpty { b.tableName },
                        fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                    if (b.customerName.isNotEmpty()) Text(
                        " · ${b.tableName}", fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    S.closedLine(clockTime(b.closedAt), b.closedByName) + " · " + S.itemsN(b.itemCount),
                    fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // the money trail, always visible on the row
                val pay = buildString {
                    if (b.paidCash > 0) append("💵 ${rupees(b.paidCash)}  ")
                    if (b.paidOnline > 0) append("📲 ${rupees(b.paidOnline)}  ")
                    if (b.discount > 0) append("· ${S.discountRow} −${rupees(b.discount)}")
                }
                if (pay.isNotEmpty()) Text(
                    pay.trim(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                    color = if (b.discount > 0) WarnAmber else GoodGreen
                )
            }
            Text(rupees(b.total), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

// Full read-back of one settled bill: the invoice card exactly as it was
// closed — items, discount, payment split — plus a reprint button.
@Composable
fun BillHistoryDetail(summary: BillSummaryDto, onBack: () -> Unit) {
    val context = LocalContext.current
    var order by remember { mutableStateOf<OrderDetailDto?>(null) }
    var bill by remember { mutableStateOf<BillDto?>(null) }
    var printHold by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(summary.orderId) {
        when (val r = Deps.api.orderDetail(summary.orderId)) {
            is ApiResult.Ok -> order = r.value
            is ApiResult.Err -> {}
        }
        when (val b = Deps.api.billGet(summary.orderId)) {
            is ApiResult.Ok -> bill = b.value
            is ApiResult.Err -> {}
        }
    }

    fun print() {
        val wv = WebView(context)
        var fired = false
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (fired) return
                fired = true
                val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
                val job = "PavBhaji bill #${summary.orderNo}"
                pm.print(job, view.createPrintDocumentAdapter(job), PrintAttributes.Builder().build())
            }
        }
        printHold = wv
        wv.loadUrl(Deps.api.billUrl(summary.orderId))
    }

    Column(Modifier.fillMaxSize()) {
        AppHeader(
            title = "${S.billTitle} #${summary.orderNo}",
            subtitle = S.closedLine(clockTime(summary.closedAt), summary.closedByName),
            onBack = onBack
        )
        val o = order
        val b = bill
        LazyColumn(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            item {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(1.dp, CardLine),
                    shadowElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        if (summary.customerName.isNotEmpty()) {
                            Text(summary.customerName, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                            Text(summary.tableName, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(summary.tableName, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        if (o != null) {
                            billLines(o).forEach { l ->
                                Row(Modifier.padding(vertical = 4.dp)) {
                                    Text(l.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    BillCol("${l.qty}", 42.dp)
                                    BillCol(rupees(l.rate), 64.dp)
                                    BillCol(rupees(l.amt), 76.dp, bold = true)
                                }
                            }
                        } else {
                            Text(S.loading, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        if (summary.discount > 0) {
                            Row(Modifier.padding(bottom = 3.dp)) {
                                Text(S.subtotalRow, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                Text(rupees(summary.subtotal), fontSize = 13.sp)
                            }
                            Row(Modifier.padding(bottom = 3.dp)) {
                                Text(
                                    S.discountRow + (if (summary.discountNote.isNotEmpty()) " · ${summary.discountNote}" else ""),
                                    fontSize = 13.sp, color = WarnAmber, modifier = Modifier.weight(1f)
                                )
                                Text("− ${rupees(summary.discount)}", fontSize = 13.sp, color = WarnAmber, fontWeight = FontWeight.Bold)
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(S.totalRow, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
                            Text(rupees(summary.total), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                        }
                        HorizontalDivider(Modifier.padding(vertical = 10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                S.paymentSection, fontSize = 10.sp, letterSpacing = 0.8.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f)
                            )
                            Text(S.paidTick, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = GoodGreen)
                        }
                        val cash = b?.paidCash ?: summary.paidCash
                        val online = b?.paidOnline ?: summary.paidOnline
                        if (cash > 0) Row(Modifier.padding(top = 6.dp)) {
                            Text("💵 ${S.cashLbl}", fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                            Text(rupees(cash), fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        }
                        if (online > 0) Row(Modifier.padding(top = 2.dp)) {
                            Text("📲 ${S.onlineLbl}", fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                            Text(rupees(online), fontSize = 13.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
        Surface(shadowElevation = 10.dp) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                BigButton(
                    label = S.printBill,
                    onClick = { print() },
                    fontSize = 16
                )
            }
        }
    }
}

@Composable
private fun BillCol(text: String, width: androidx.compose.ui.unit.Dp, muted: Boolean = false, bold: Boolean = false) {
    Text(
        text,
        fontSize = if (muted) 10.sp else 13.sp,
        letterSpacing = if (muted) 0.8.sp else 0.sp,
        fontWeight = if (bold || muted) FontWeight.Bold else FontWeight.Normal,
        color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.End,
        modifier = Modifier.padding(start = 4.dp).width(width)
    )
}
