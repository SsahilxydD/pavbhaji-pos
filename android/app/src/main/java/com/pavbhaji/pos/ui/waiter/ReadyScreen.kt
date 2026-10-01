package com.pavbhaji.pos.ui.waiter

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pavbhaji.pos.Deps
import com.pavbhaji.pos.data.ApiResult
import com.pavbhaji.pos.data.PickupLineDto
import com.pavbhaji.pos.ui.AppHeader
import com.pavbhaji.pos.ui.CardLine
import com.pavbhaji.pos.ui.EmptyState
import com.pavbhaji.pos.ui.ErrorBanner
import com.pavbhaji.pos.ui.GoodGreen
import com.pavbhaji.pos.ui.LogoutAction
import com.pavbhaji.pos.ui.ListSkeleton
import com.pavbhaji.pos.ui.Pb
import com.pavbhaji.pos.ui.PinBelowHistory
import com.pavbhaji.pos.ui.S
import com.pavbhaji.pos.ui.WarnAmber
import com.pavbhaji.pos.ui.ageMinutes
import com.pavbhaji.pos.ui.dishColour
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The manager's WATCH list, grouped BY TABLE: what's waiting to be carried,
// most-starved table on top. Purely for coordination — serving itself is the
// waiters' power alone, one plate at a time from their own list, so this
// screen has no buttons. Counter items (drinks, extra pav) ride the same
// card tinted amber: nothing was cooked, a waiter just carries them.
@Composable
fun ReadyScreen(onLogout: () -> Unit, showLogout: Boolean, onBack: (() -> Unit)? = null) {
    var lines by remember { mutableStateOf<List<PickupLineDto>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        when (val r = Deps.api.pickup()) {
            is ApiResult.Ok -> { lines = r.value.lines; loadError = null; loaded = true }
            is ApiResult.Err -> loadError = r.message
        }
    }
    LaunchedEffect(Unit) {
        refresh()
        // "menu" too: an admin recolour must repaint the dish dots live.
        Deps.live.topics.collect { if (it == "kitchen" || it == "tables" || it == "menu") refresh() }
    }
    LaunchedEffect(Unit) { while (true) { delay(15_000); refresh() } }

    val groups = lines.groupBy { it.tableName }
        .map { (t, ls) -> ReadyGroup(t, ls.minOf { it.sinceMs }, ls.sortedBy { it.sinceMs }) }
        .sortedWith(compareBy({ it.since }, { it.tableName }))

    Column(Modifier.fillMaxSize()) {
        AppHeader(
            title = S.readyTitle,
            subtitle = when {
                !loaded -> if (loadError != null) S.offlineShort else S.loading
                lines.isEmpty() -> S.nothingToCarry
                groups.size == 1 -> S.toCarryOneTable(lines.size)
                else -> S.toCarryTables(lines.size, groups.size)
            },
            // Inside the admin's Floor this is a door like any other: the
            // arrow (and system back) lead home instead of out of the app.
            onBack = onBack,
            actions = { if (showLogout) LogoutAction(onLogout) }
        )
        ErrorBanner(loadError)
        when {
            !loaded -> ListSkeleton(rows = 4, rowHeight = 86.dp)
            lines.isEmpty() -> EmptyState("🛎️", S.allCarried)
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(groups.size, key = { groups[it].tableName }) { i ->
                    TableCarryCard(groups[i], Modifier.animateItem())
                }
                items(1) { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

private data class ReadyGroup(
    val tableName: String,
    val since: Long,
    val lines: List<PickupLineDto>
)

@Composable
private fun TableCarryCard(g: ReadyGroup, modifier: Modifier = Modifier) {
    // Spine green when the kitchen cooked something here; amber when this
    // trip is only counter hand-overs.
    val allCounter = g.lines.all { it.station == "counter" }
    val spine = if (allCounter) Pb.counterSpine else Pb.successLine
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (allCounter) Pb.counterBg else Color.White,
        border = BorderStroke(1.dp, CardLine),
        shadowElevation = 1.dp,
        modifier = modifier
    ) {
        Row(Modifier.height(IntrinsicSize.Min).fillMaxWidth()) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(spine))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        g.tableName, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.3).sp, modifier = Modifier.weight(1f)
                    )
                    ReadyAge(g.since)
                }
                g.lines.forEach { l ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            Modifier.size(11.dp).background(
                                dishColour(l.colour, l.itemId).copy(alpha = if (l.waiting) 0.35f else 1f),
                                RoundedCornerShape(4.dp)
                            )
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${l.qty}× ${l.name}", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (l.waiting) Pb.ink3 else Color.Unspecified
                            )
                            val sub = listOfNotNull(
                                if (l.note.isNotEmpty()) "⚠ ${l.note}" else null,
                                when {
                                    l.waiting -> S.waitsForBhaji(l.waitingOn)
                                    l.station == "counter" -> S.justCarry
                                    else -> null
                                }
                            ).joinToString(" · ")
                            if (sub.isNotEmpty()) Text(
                                sub, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = if (l.note.isNotEmpty()) MaterialTheme.colorScheme.error
                                else Pb.ink2
                            )
                        }
                    }
                }
            }
        }
    }
}

// One trip per table. Sorting purely by age scattered a table across the list
// (T1 in rows 1, 2 and 7) and put a drink punched early above hot food that
// came off the tawa later. Now a table's plates sit together, the table that
// has waited longest on top; inside a table hot kitchen plates come first,
// drinks and counter items after, anything held for the bhaji last. Tables
// with nothing carryable yet (all held) sink below the ones a waiter can act on.
private fun carryOrder(lines: List<PickupLineDto>): List<PickupLineDto> =
    lines.groupBy { it.tableName }.values
        .sortedWith(
            compareBy<List<PickupLineDto>>(
                { g -> g.all { it.waiting } },
                { g -> g.filter { !it.waiting }.minOfOrNull { it.sinceMs } ?: g.minOf { it.sinceMs } },
                { g -> g.first().tableName }
            )
        )
        .flatMap { g ->
            g.sortedWith(compareBy({ it.waiting }, { it.station == "counter" }, { it.sinceMs }, { it.itemId }))
        }

// The waiter's ENTIRE app: one flat vertical list — every plate to carry right
// now, a table at a time. Each row reads like a call-out: "2× Cheese Bhaji → T4 ✓".
// Waiters never take orders (that's the manager's job); they only serve, so
// there are no tabs, no tables grid, nothing else to learn.
@Composable
fun ServeScreen(onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<PickupLineDto>>(emptyList()) }
    var served by remember { mutableStateOf<List<PickupLineDto>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    // Plates tapped served whose answer is still on the wire. They cross to
    // the served pile the moment the thumb lands (⏳ until the server says
    // yes) — on slow 4G a row that sat there looked like a missed tap — and
    // come back with the reason if the server says no.
    val inFlight = remember { mutableStateMapOf<Long, PickupLineDto>() }

    suspend fun refresh() {
        when (val r = Deps.api.pickup()) {
            is ApiResult.Ok -> {
                // A fetch that started before the tap must not undo it on screen.
                lines = r.value.lines.filter { it.itemId !in inFlight }
                served = inFlight.values.filter { f -> r.value.served.none { it.itemId == f.itemId } } +
                    r.value.served
                loadError = null; loaded = true
            }
            is ApiResult.Err -> loadError = r.message
        }
    }
    LaunchedEffect(Unit) {
        refresh()
        Deps.live.topics.collect { if (it == "kitchen" || it == "tables" || it == "menu") refresh() }
    }
    LaunchedEffect(Unit) { while (true) { delay(15_000); refresh() } }
    toast?.let { LaunchedEffect(it) { delay(2500); toast = null } }

    val sorted = carryOrder(lines)
    // History renders oldest→newest, so the most recent serve sits directly
    // above the live list — one small scroll up answers "what did I just take?".
    val history = served.reversed()

    // Served rows pile up ABOVE the live list and drift out of the top of the
    // screen; the SERVE NOW label stays pinned, so the buttons never move
    // under a thumb. Scrolling up re-reads what already went out.
    val listState = rememberLazyListState()
    val boundary = if (history.isEmpty()) 0 else history.size + 1
    PinBelowHistory(listState, boundary)

    Column(Modifier.fillMaxSize()) {
        AppHeader(
            title = S.serveTitle,
            // Never "nothing to carry" before the list has actually loaded:
            // offline, that would send a waiter away from food at the pass.
            subtitle = when {
                !loaded -> if (loadError != null) S.offlineShort else S.loading
                lines.isEmpty() -> S.nothingToCarry
                else -> S.toCarry(lines.size)
            },
            actions = { LogoutAction(onLogout) }
        )
        ErrorBanner(loadError)
        when {
            !loaded -> ListSkeleton(rows = 5, rowHeight = 72.dp)
            lines.isEmpty() && history.isEmpty() -> EmptyState("🛎️", S.allCarried)
            else -> BoxWithConstraints(Modifier.fillMaxSize()) {
                // The pin only holds if a viewport of content exists below the
                // boundary; pad the tail so short lists anchor too (64dp is a
                // low estimate of a row, erring towards a longer tail).
                val filler =
                    if (history.isEmpty()) 24.dp
                    else maxOf(24.dp, maxHeight - (if (lines.isEmpty()) 128.dp else 64.dp * sorted.size))
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (history.isNotEmpty()) {
                        item(key = "hdrServed") { SectionLabel(S.servedSection) }
                        items(history.size, key = { "h${history[it].itemId}" }) { i ->
                            HistoryRow(history[i], pending = history[i].itemId in inFlight, modifier = Modifier.animateItem())
                        }
                        item(key = "hdrNow") { SectionLabel(S.serveNow) }
                    }
                    if (lines.isEmpty()) {
                        item(key = "allDone") { EmptyState("🛎️", S.allCarried) }
                    }
                    items(sorted.size, key = { "a${sorted[it].itemId}" }) { i ->
                        val l = sorted[i]
                        // A little air where the next table starts.
                        val newTable = i > 0 && sorted[i - 1].tableName != l.tableName
                        ServeRow(
                            l,
                            modifier = Modifier.animateItem().padding(top = if (newTable) 6.dp else 0.dp),
                            onServe = {
                                if (l.itemId !in inFlight) {
                                    // Across the boundary in one step, now — never a
                                    // beat where the plate is nowhere.
                                    val mine = l.copy(servedBy = Deps.session.staffName)
                                    inFlight[l.itemId] = mine
                                    lines = lines.filter { it.itemId != l.itemId }
                                    served = listOf(mine) + served
                                    scope.launch {
                                        when (val r = Deps.api.itemStatus(l.itemId, "served")) {
                                            is ApiResult.Ok -> {}
                                            is ApiResult.Err -> {
                                                // Not served after all: back on the list, and why.
                                                toast = r.message
                                                served = served.filter { it.itemId != l.itemId }
                                                if (lines.none { it.itemId == l.itemId }) lines = lines + l
                                            }
                                        }
                                        inFlight.remove(l.itemId)
                                        refresh()
                                    }
                                }
                            }
                        )
                    }
                    item(key = "tail") { Spacer(Modifier.height(filler)) }
                }
                // Errors float over the list instead of shoving it down.
                toast?.let {
                    Surface(
                        color = Color.White,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, CardLine),
                        shadowElevation = 6.dp,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                    ) {
                        Text(
                            it, color = MaterialTheme.colorScheme.error,
                            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = com.pavbhaji.pos.ui.tracking(1f),
        color = Pb.ink3,
        modifier = Modifier.padding(top = 2.dp)
    )
}

// A plate that already went out: greyed, no button — pure memory. The table
// number stays loud because "where did that plate go?" is the question here.
// pending = tapped, the server hasn't confirmed yet: ⏳ instead of ✓.
@Composable
private fun HistoryRow(l: PickupLineDto, pending: Boolean = false, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFF5F1EA),
        border = BorderStroke(1.dp, Color(0xFFE9E2D6)),
        modifier = modifier
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (pending) "⏳" else "✓", color = GoodGreen, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.semantics { contentDescription = if (pending) S.sending else S.statusLabel("served") }
            )
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                Text(
                    "${l.qty}× ${l.name}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, color = Pb.ink2
                )
                Text(
                    listOfNotNull(ageMinutes(l.sinceMs), l.servedBy.ifEmpty { null }).joinToString(" · "),
                    fontSize = 11.sp, color = Pb.ink3
                )
            }
            Text(
                l.tableName, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
                color = Pb.ink2,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

@Composable
private fun ServeRow(l: PickupLineDto, onServe: () -> Unit, modifier: Modifier = Modifier) {
    val counter = l.station == "counter"
    // A held line (extra pav while the table's bhaji is still on the tawa) keeps
    // its slot in the queue so nobody forgets it, but reads as not-yours-yet:
    // quiet card, dimmed text, an hourglass where the button would be. The age
    // chip is hidden too — it only nags about time the waiter can't act on.
    val held = l.waiting
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = when {
            held -> Pb.quietCard
            counter -> Pb.counterBg
            else -> Color.White
        },
        border = BorderStroke(1.dp, CardLine),
        shadowElevation = if (held) 0.dp else 1.dp,
        modifier = modifier
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(11.dp).background(
                    dishColour(l.colour, l.itemId).copy(alpha = if (held) 0.35f else 1f),
                    RoundedCornerShape(4.dp)
                )
            )
            // The dish is what the waiter picks up, so it gets the whole middle
            // of the row and wraps to a second line before it ever truncates
            // (big system font, Hindi, Gujarati). Age and notes sit under it.
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                Text(
                    "${l.qty}× ${l.name}", fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    color = if (held) Pb.ink3 else Color.Unspecified
                )
                val sub = listOfNotNull(
                    if (l.note.isNotEmpty()) "⚠ ${l.note}" else null,
                    when {
                        held -> S.waitsForBhaji(l.waitingOn)
                        counter -> S.justCarry
                        else -> null
                    }
                ).joinToString(" · ")
                if (!held || sub.isNotEmpty()) Row(
                    Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (!held) ReadyAge(l.sinceMs)
                    if (sub.isNotEmpty()) Text(
                        sub, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (l.note.isNotEmpty()) MaterialTheme.colorScheme.error
                        else Pb.ink2
                    )
                }
            }
            // The table number is what a waiter scans the list for — loudest thing in the row.
            Text(
                l.tableName, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                color = if (held) Pb.ink3 else Color.Unspecified,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
            if (held) HeldPill() else ServeButton(counter = counter, onClick = onServe)
        }
    }
}

// Where the serve button sits on a held line: same footprint so the list keeps
// its rhythm, but nothing to press until the kitchen lets the plate go.
@Composable
private fun HeldPill() {
    Surface(color = Pb.skeleton, shape = RoundedCornerShape(12.dp)) {
        Box(Modifier.defaultMinSize(minWidth = 56.dp, minHeight = 48.dp), contentAlignment = Alignment.Center) {
            Text("⏳", fontSize = 17.sp, maxLines = 1)
        }
    }
}

// The one button a waiter presses all day: green (amber for counter items),
// springs under the thumb, clicks like a key. A fat ✓ instead of the words —
// "Served ✓" ate the dish name at big font sizes; the words still go to
// TalkBack.
@Composable
private fun ServeButton(counter: Boolean, onClick: () -> Unit) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.92f else 1f,
        spring(dampingRatio = 0.6f, stiffness = 1200f), label = "serve"
    )
    Surface(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        },
        interactionSource = interaction,
        color = if (counter) WarnAmber else Pb.successDeep,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .semantics { contentDescription = S.servedBtn }
    ) {
        Box(Modifier.defaultMinSize(minWidth = 56.dp, minHeight = 48.dp), contentAlignment = Alignment.Center) {
            Text(
                "✓", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
                modifier = Modifier.clearAndSetSemantics {}
            )
        }
    }
}

@Composable
private fun ReadyAge(sinceMs: Long) {
    val m = ((System.currentTimeMillis() - sinceMs) / 60_000).coerceAtLeast(0)
    val (bg, fg) = when {
        m >= 15 -> Color(0xFFE14B3C) to Color.White
        m >= 8 -> Color(0x33F5A623) to Color(0xFF8A5A00)
        else -> Color.Transparent to GoodGreen
    }
    Surface(color = bg, shape = RoundedCornerShape(8.dp)) {
        Text(
            "${m}m", color = fg, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
