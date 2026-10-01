package com.pavbhaji.pos.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pavbhaji.pos.Deps
import com.pavbhaji.pos.data.ApiResult
import com.pavbhaji.pos.data.AuditEventDto
import com.pavbhaji.pos.data.ReportDto
import com.pavbhaji.pos.ui.GoodGreen
import com.pavbhaji.pos.ui.S
import com.pavbhaji.pos.ui.SectionTitle
import com.pavbhaji.pos.ui.StatCard
import com.pavbhaji.pos.ui.clockTime
import com.pavbhaji.pos.ui.rupees
import kotlinx.coroutines.launch
import java.util.Calendar

private fun shiftDate(date: String, days: Int): String {
    return try {
        val p = date.split("-").map { it.toInt() }
        val cal = Calendar.getInstance()
        cal.set(p[0], p[1] - 1, p[2])
        cal.add(Calendar.DAY_OF_MONTH, days)
        "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
    } catch (e: Exception) { date }
}

@Composable
fun ReportScreen(onBack: () -> Unit, onOpenLog: () -> Unit) {
    var date by remember { mutableStateOf<String?>(null) } // null = today
    var rep by remember { mutableStateOf(ReportDto()) }
    // Until the day's numbers arrive the page used to show ₹0 everywhere — a
    // real-looking report of nothing, and the same when offline. Now: a
    // skeleton while loading, and the reason when it fails.
    var loaded by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(date, attempt) {
        loaded = false
        when (val r = Deps.api.report(date)) {
            is ApiResult.Ok -> { rep = r.value; loaded = true; err = null }
            is ApiResult.Err -> err = r.message
        }
    }

    Column(Modifier.fillMaxSize()) {
        com.pavbhaji.pos.ui.AppHeader(
            title = S.navReport,
            onBack = onBack,
            actions = { com.pavbhaji.pos.ui.HeaderTextAction(S.logBtn, onOpenLog) }
        )
        com.pavbhaji.pos.ui.ErrorBanner(err)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            val known = rep.date.isNotEmpty()
            TextButton(onClick = { date = shiftDate(rep.date, -1) }, enabled = known) { Text("◀") }
            Text(rep.date.ifEmpty { "…" }, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            TextButton(onClick = { date = shiftDate(rep.date, +1) }, enabled = known) { Text("▶") }
            TextButton(onClick = { date = null }) { Text(S.todayBtn, fontSize = 12.sp) }
        }
        if (!loaded) {
            com.pavbhaji.pos.ui.ListSkeleton(rows = 4, rowHeight = 80.dp)
            if (err != null) {
                TextButton(
                    onClick = { attempt++ },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) { Text(S.retryBtn) }
            }
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(S.salesServed, rupees(rep.soldTotal), Modifier.weight(1f), GoodGreen)
                StatCard(S.ordersCard, "${rep.ordersOpened}", Modifier.weight(1f))
                StatCard(S.cancelledCard, rupees(rep.cancelledTotal), Modifier.weight(1f), MaterialTheme.colorScheme.error)
            }
            // The drawer view: how the day's closed bills were settled.
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard(S.reportCash, rupees(rep.paidCash), Modifier.weight(1f))
                StatCard(S.reportOnline, rupees(rep.paidOnline), Modifier.weight(1f))
                StatCard(S.reportDiscounts, rupees(rep.discountTotal), Modifier.weight(1f), com.pavbhaji.pos.ui.WarnAmber)
            }
            if (rep.ordersOpen > 0) {
                Text(
                    S.stillOpen(rep.ordersOpen.toLong()),
                    color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            SectionTitle(S.soldSection)
            com.pavbhaji.pos.ui.PbCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (rep.sold.isEmpty()) Text(S.nothingYet, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    rep.sold.forEach {
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text("${it.qty}× ${it.name}", fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(rupees(it.value), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text(S.totalRow, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text(rupees(rep.soldTotal), fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = GoodGreen)
                    }
                }
            }

            SectionTitle(S.byWaiter)
            com.pavbhaji.pos.ui.PbCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (rep.waiters.isEmpty()) Text(S.nothingYet, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    rep.waiters.forEach {
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text("${it.name} (${S.itemsN(it.qty)})", fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(rupees(it.value), fontSize = 14.sp)
                        }
                    }
                }
            }

            SectionTitle(S.cancelledSection)
            com.pavbhaji.pos.ui.PbCard(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (rep.cancelled.isEmpty()) Text(S.noneParty, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    rep.cancelled.forEach {
                        Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                            Text("${it.qty}× ${it.name}", fontSize = 14.sp, modifier = Modifier.weight(1f))
                            Text(rupees(it.value), fontSize = 14.sp, color = MaterialTheme.colorScheme.error)
                        }
                    }
                    rep.cancelLog.forEach {
                        Text(
                            "${clockTime(it.at)} · ${it.by}: ${it.detail}",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
fun AuditScreen(onBack: () -> Unit) {
    var events by remember { mutableStateOf<List<AuditEventDto>>(emptyList()) }
    var loadingMore by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") } // "" = all, else an auditGroups key
    var loaded by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LaunchedEffect(filter, attempt) {
        events = emptyList()
        loaded = false
        when (val r = Deps.api.audit(type = filter)) {
            is ApiResult.Ok -> { events = r.value.events; loaded = true; err = null }
            is ApiResult.Err -> err = r.message
        }
    }

    Column(Modifier.fillMaxSize()) {
        com.pavbhaji.pos.ui.AppHeader(title = S.auditTitle, onBack = onBack)
        com.pavbhaji.pos.ui.ErrorBanner(err)
        // Filter by the KIND of event — the "what happened with bills today?"
        // question shouldn't need scrolling past every KOT.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("", "orders", "kitchen", "serving", "cancel", "billing", "staff").forEach { key ->
                androidx.compose.material3.FilterChip(
                    selected = filter == key,
                    onClick = { filter = key },
                    label = { Text(S.auditFilter(key), fontSize = 12.sp) }
                )
            }
        }
        if (!loaded) {
            com.pavbhaji.pos.ui.ListSkeleton(rows = 6, rowHeight = 46.dp)
            if (err != null) {
                TextButton(
                    onClick = { attempt++ },
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) { Text(S.retryBtn) }
            }
            return@Column
        }
        if (events.isEmpty()) {
            com.pavbhaji.pos.ui.EmptyState("🗒️", S.nothingYet)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            events.forEach { e ->
                item(key = e.id) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        Row {
                            Text(
                                "${clockTime(e.at)} · ${e.by.ifEmpty { S.system }}",
                                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f)
                            )
                            // "batch:plate-ready" → "plate ready": words, not codes.
                            Text(S.auditAction(e.action), fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(e.detail, fontSize = 13.sp)
                    }
                }
            }
            item {
                if (events.isNotEmpty()) {
                    Button(
                        onClick = {
                            if (!loadingMore) {
                                loadingMore = true
                                scope.launch {
                                    when (val r = Deps.api.audit(beforeId = events.last().id, type = filter)) {
                                        is ApiResult.Ok -> { events = events + r.value.events; err = null }
                                        is ApiResult.Err -> err = r.message
                                    }
                                    loadingMore = false
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                    ) { Text(if (loadingMore) S.loading else S.loadOlder) }
                }
            }
        }
    }
}
