package com.pavbhaji.pos.ui.chef

import android.app.Activity
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pavbhaji.pos.Deps
import com.pavbhaji.pos.data.ApiResult
import com.pavbhaji.pos.data.BucketDto
import com.pavbhaji.pos.data.BucketLineDto
import com.pavbhaji.pos.data.KitchenDto
import com.pavbhaji.pos.ui.ConnBanner
import com.pavbhaji.pos.ui.Kc
import com.pavbhaji.pos.ui.S
import com.pavbhaji.pos.ui.dishColour
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel

// The chef's screen is a queue of colour buckets, oldest first.
// Tap a bucket to open its lines; "Take" locks it to this chef; tapping a
// bhaji inside sends THAT bhaji to the pass the instant it is done, and
// "Leave bucket" hands the bucket back with the rest still cooking.
@Composable
fun KitchenScreen(onLogout: () -> Unit, embedded: Boolean = false, onBack: (() -> Unit)? = null) {
    val connected by Deps.live.connected.collectAsState()
    var data by remember { mutableStateOf(KitchenDto()) }
    var loaded by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val myId = Deps.session.staffId
    com.pavbhaji.pos.ui.StatusBarIcons(darkIcons = false)

    // Kitchen phone should never dim mid-rush.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Beep when a bucket we've never seen shows up, or plates join one we
    // have. One generator for the screen's life, released when it goes (a
    // new one per beep leaked audio tracks over a night). ALARM stream, so
    // it rings with the media volume at zero, plus a buzz for a phone lying
    // on the steel counter.
    val seenBuckets = remember { mutableSetOf<Long>() }
    val lastQty = remember { mutableMapOf<Long, Long>() }
    var firstLoad by remember { mutableStateOf(true) }
    val beeper = remember {
        if (embedded) null
        else try { ToneGenerator(AudioManager.STREAM_ALARM, 90) } catch (_: Exception) { null }
    }
    DisposableEffect(beeper) { onDispose { beeper?.release() } }
    val view = LocalView.current

    // Every chef action reaches the server through ONE queue, one request at
    // a time, in tap order. Nothing is dropped because an earlier request is
    // still on the wire — a chef flicks plates off the tawa faster than a
    // phone on 4G answers — and a "Leave" tapped after two plates lands
    // after them.
    val actions = remember { Channel<suspend () -> Unit>(Channel.UNLIMITED) }
    var queued by remember { mutableIntStateOf(0) }
    fun enqueue(action: suspend () -> Unit) { queued++; actions.trySend(action) }
    LaunchedEffect(actions) {
        for (action in actions) {
            try { action() } finally { queued-- }
        }
    }
    // Plates tapped but not yet confirmed, per plateKey: they show ticked the
    // moment the finger lands, and stop counting in the same step the
    // server's copy starts showing them.
    val ticking = remember { mutableStateMapOf<String, Int>() }
    val fetches = remember { FetchOrder() }

    suspend fun refresh(fromQueue: Boolean = false) {
        val seq = ++fetches.started
        val r = Deps.api.kitchen()
        // An answer older than one already shown would undo a tick on screen.
        if (seq < fetches.applied) return
        // While taps wait in the queue only the queue's own fetches land, so
        // a plate is never counted both as ticking and as already done.
        if (!fromQueue && queued > 0) return
        fetches.applied = seq
        when (r) {
            is ApiResult.Ok -> {
                val fresh = r.value.buckets.map { it.id }.filter { it !in seenBuckets }
                val grew = r.value.buckets.any { b -> b.id in seenBuckets && b.qty > (lastQty[b.id] ?: 0L) }
                if ((fresh.isNotEmpty() || grew) && !firstLoad && !embedded) {
                    try { beeper?.startTone(ToneGenerator.TONE_PROP_BEEP2, 500) } catch (_: Exception) {}
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                seenBuckets.addAll(fresh)
                lastQty.clear()
                r.value.buckets.forEach { lastQty[it.id] = it.qty }
                firstLoad = false
                data = r.value
                loaded = true
                loadError = null
                // My cooking bucket stays open in front of me; a bucket that
                // closed (last variety ticked, or finished elsewhere) lets go.
                if (expandedId != null && r.value.buckets.none { it.id == expandedId }) {
                    expandedId = null
                }
                if (expandedId == null) {
                    expandedId = r.value.buckets.firstOrNull { it.status == "taken" && it.takenBy == myId }?.id
                }
            }
            is ApiResult.Err -> loadError = r.message
        }
    }
    LaunchedEffect(Unit) {
        refresh()
        // "menu" too: an admin recolour must repaint the buckets live.
        Deps.live.topics.collect { if (it == "kitchen" || it == "tables" || it == "menu") refresh() }
    }
    LaunchedEffect(Unit) {
        while (true) { delay(15_000); refresh() }
    }
    toast?.let { LaunchedEffect(it) { delay(3000); toast = null } }

    // Take / finish / leave a whole bucket: one at a time (a double tap must
    // not take twice), queued behind any plate ticks already tapped.
    fun bucketAction(call: suspend () -> Unit) {
        if (busy) return
        busy = true
        enqueue { call(); refresh(fromQueue = true); busy = false }
    }

    // Sends one plate of `key` to the pass. Plates of a line share its id only
    // until the server splits it (the done plate keeps the id, the rest move
    // to a new row), so the line is looked up in the freshest copy for every
    // tap. If that copy turns out stale (server sent 0 plates), re-read, retry.
    suspend fun sendPlate(batchId: Long, key: String) {
        repeat(2) {
            val b = data.buckets.firstOrNull { it.id == batchId }
            val line = b?.lines?.firstOrNull { !it.done && it.id != 0L && plateKey(b, it) == key }
            if (line == null) { refresh(fromQueue = true); return@repeat }
            when (val r = Deps.api.readyPlate(batchId, line.id)) {
                is ApiResult.Ok -> { refresh(fromQueue = true); if (r.value.plates > 0) return }
                is ApiResult.Err -> { toast = r.message; refresh(fromQueue = true); return }
            }
        }
    }

    if (onBack != null) BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(Kc.bg)) {
        // Dark header — the brand red bar lives on the login screen only.
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .heightIn(min = 56.dp)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Kc.ink)
                }
            } else {
                Text("👨‍🍳", fontSize = 20.sp, modifier = Modifier.padding(start = 10.dp, end = 2.dp))
            }
            Text(S.kitchenTitle, color = Kc.ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(start = 6.dp))
            Text(
                S.bucketsSub(data.buckets.size, Deps.session.staffName),
                color = Kc.ink2, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp).weight(1f)
            )
            com.pavbhaji.pos.ui.LangAction(tint = Kc.ink2)
            Box(Modifier.size(7.dp).background(if (connected) Kc.ready else Kc.late, CircleShape))
            if (onBack == null) {
                com.pavbhaji.pos.ui.LogoutAction(onLogout, tint = Kc.ink2, fontSize = 13)
            } else {
                Spacer(Modifier.width(10.dp))
            }
        }
        ConnBanner(connected)
        com.pavbhaji.pos.ui.ErrorBanner(loadError, dark = true)
        toast?.let {
            Text(
                it, color = Kc.late, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp)
            )
        }

        if (!loaded) {
            com.pavbhaji.pos.ui.ListSkeleton(rows = 4, rowHeight = 84.dp, base = Kc.card)
        } else if (data.buckets.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(S.noBuckets, color = Kc.ink2, fontSize = 14.sp)
            }
        } else {
            // The bucket this chef is cooking is always the first card — it
            // used to keep its queue place and sat 4th, half off the screen.
            // The rest stay oldest first.
            val mineFirst = data.buckets.sortedByDescending { it.status == "taken" && it.takenBy == myId }
            val myBucket = mineFirst.firstOrNull()?.takeIf { it.status == "taken" && it.takenBy == myId }?.id
            val listState = rememberLazyListState()
            // Taking a bucket moves it to the top; follow it there (the list
            // would otherwise stay on whatever card was at the top before).
            LaunchedEffect(myBucket) { if (myBucket != null) listState.animateScrollToItem(0) }
            LazyColumn(
                Modifier.fillMaxSize(),
                state = listState,
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val myBusy = myBucket != null
                items(mineFirst.size, key = { mineFirst[it].id }) { i ->
                    val b = mineFirst[i]
                    BucketCard(
                        modifier = Modifier.animateItem(),
                        b = b, cap = if (b.cap > 0) b.cap else data.cap, myId = myId, myBusy = myBusy,
                        expanded = expandedId == b.id,
                        ticking = ticking,
                        onTap = { expandedId = if (expandedId == b.id) null else b.id },
                        onTake = {
                            bucketAction {
                                when (val r = Deps.api.takeBatch(b.id)) {
                                    is ApiResult.Ok -> expandedId = b.id
                                    is ApiResult.Err -> toast = r.message
                                }
                            }
                        },
                        onReady = {
                            bucketAction {
                                when (val r = Deps.api.readyBatch(b.id)) {
                                    is ApiResult.Ok -> if (expandedId == b.id) expandedId = null
                                    is ApiResult.Err -> toast = r.message
                                }
                            }
                        },
                        onReadyVariety = { menuItemId ->
                            // One tap = that bhaji is done. It goes straight to
                            // the pass; whatever is still on the tawa stays in
                            // the bucket, cooking.
                            bucketAction {
                                when (val r = Deps.api.readyBatch(b.id, menuItemId)) {
                                    is ApiResult.Ok -> {}
                                    is ApiResult.Err -> toast = r.message
                                }
                            }
                        },
                        onReadyPlate = { key ->
                            // One tap = ONE plate. Four of the same bhaji for
                            // one table are four rows and four taps: the one
                            // that is done leaves for the pass, the other three
                            // stay on the tawa. The row ticks now; the queue
                            // sends it as soon as the taps before it are in.
                            ticking[key] = (ticking[key] ?: 0) + 1
                            enqueue {
                                sendPlate(b.id, key)
                                // Same step as sendPlate's last fetch: from here
                                // the server's copy says whether it went out.
                                val left = (ticking[key] ?: 1) - 1
                                if (left > 0) ticking[key] = left else ticking.remove(key)
                            }
                        },
                        onLeave = {
                            // Step away without finishing: the bucket unlocks
                            // with its plates still inside, so this chef can
                            // start another colour and come back to it.
                            bucketAction {
                                when (val r = Deps.api.releaseBatch(b.id)) {
                                    is ApiResult.Ok -> if (expandedId == b.id) expandedId = null
                                    is ApiResult.Err -> toast = r.message
                                }
                            }
                        }
                    )
                }
                items(1) { Spacer(Modifier.height(30.dp)) }
            }
        }
    }
}

private fun minsSince(ms: Long): Long =
    ((System.currentTimeMillis() - ms) / 60_000).coerceAtLeast(0)

// How long the food in this bucket has waited: its oldest plate still on the
// tawa. Not the bucket's own birth — an open bucket left over from last
// night took the day's first order and read "526m" in red.
private fun bucketAge(b: BucketDto): Long =
    b.lines.filter { !it.done && it.since > 0 }.minOfOrNull { it.since } ?: b.createdAt

@Composable
private fun AgeChip(sinceMs: Long) {
    val m = minsSince(sinceMs)
    val (bg, fg) = when {
        m >= 15 -> Kc.late to Color.White
        m >= 8 -> Kc.warn to Kc.warnFg
        else -> Color.Transparent to Kc.ink2
    }
    Surface(color = bg, shape = RoundedCornerShape(7.dp)) {
        Text(
            "${m}m", color = fg, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun StateChip(text: String, bg: Color, fg: Color, dashed: Boolean = false) {
    Surface(
        color = bg, shape = RoundedCornerShape(8.dp),
        border = if (dashed) androidx.compose.foundation.BorderStroke(1.dp, Kc.ink2.copy(alpha = 0.6f)) else null
    ) {
        Text(
            text, color = fg, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.5.sp,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun BucketCard(
    b: BucketDto, cap: Long, myId: Long, myBusy: Boolean,
    expanded: Boolean, ticking: Map<String, Int>,
    onTap: () -> Unit, onTake: () -> Unit, onReady: () -> Unit,
    onReadyVariety: (Long) -> Unit, onReadyPlate: (String) -> Unit, onLeave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colour = dishColour(b.colour, b.menuItemId)
    val mine = b.status == "taken" && b.takenBy == myId
    val lockedByOther = b.status == "taken" && b.takenBy != myId

    Surface(
        color = Kc.card,
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, if (mine) colour.copy(alpha = 0.55f) else Kc.border
        ),
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (lockedByOther) 0.62f else 1f)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onTap)
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // dish colour band down the left edge
            Box(Modifier.width(6.dp).fillMaxHeight().background(colour))
            Column(Modifier.weight(1f)) {
                // The name owns the top line: every dp the counts don't need,
                // up to two lines, and a visible "…" when even that is short.
                // A clubbed bucket reads "Pav Bhaji + Paneer Pav Bhaji + …",
                // and two buckets never both shrink to "Sp.". The state chip
                // rides on the fill-bar line below so it can't squeeze the name.
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 11.dp, top = 11.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.size(15.dp).background(colour, RoundedCornerShape(5.dp)))
                    Text(
                        b.name, color = Kc.ink, fontSize = 14.5.sp, fontWeight = FontWeight.ExtraBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        "${b.qty}", color = Kc.ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold
                    )
                    Text("/$cap", color = Kc.ink2, fontSize = 11.sp)
                    if (b.status != "taken") AgeChip(bucketAge(b))
                }
                // state chip + fill bar
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    when {
                        mine -> StateChip(S.yoursChip(minsSince(b.takenAt)), Kc.mineBg, Kc.mineFg)
                        lockedByOther -> StateChip("🔒 ${b.takenByName}", Kc.lockBg, Kc.lockFg)
                        b.status == "full" -> StateChip(S.fullChip, Kc.fullBg, Kc.fullFg)
                        else -> StateChip(S.fillingChip, Color.Transparent, Kc.ink2, dashed = true)
                    }
                    // Plates landing in the bucket pull the bar smoothly, not in jumps.
                    val fill by animateFloatAsState(
                        (b.qty.toFloat() / cap).coerceIn(0f, 1f),
                        spring(dampingRatio = 0.9f, stiffness = 220f),
                        label = "fill"
                    )
                    Box(
                        Modifier.weight(1f).height(5.dp)
                            .background(Kc.border, RoundedCornerShape(99.dp))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fill)
                                .height(5.dp)
                                .background(colour, RoundedCornerShape(99.dp))
                        )
                    }
                    if (!expanded) {
                        val hint = when {
                            lockedByOther -> S.hintLocked
                            mine -> S.hintMine
                            myBusy -> S.hintAfterYours
                            b.status == "full" -> S.hintSealed
                            else -> S.hintTapTables
                        }
                        // Capped, so a long hint at a big font can't squeeze
                        // the fill bar out beside the state chip.
                        Text(
                            hint, color = Kc.ink2, fontSize = 10.5.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            modifier = Modifier.widthIn(max = 150.dp)
                        )
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = expanded,
                    enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
                ) {
                    Column(
                        Modifier.fillMaxWidth().background(Kc.inside)
                            .padding(start = 10.dp, end = 12.dp, top = 9.dp, bottom = 11.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        // Nothing on the tawa is clubbed here: every plate is its
                        // own row. A table that ordered 4 bhajis does not get
                        // all 4 off the pan in the same second, so 4 plates are
                        // 4 ticks. The varieties are only headings — they tell
                        // the chef what is left of each, they are not buttons.
                        // (id==0 means an old server that cannot split a line:
                        // fall back to whole-variety ticks, then to one button.)
                        val perPlate = mine && b.lines.isNotEmpty() && b.lines.all { it.id != 0L }
                        val perVariety = mine && !perPlate && b.lines.all { it.menuItemId != 0L }
                        if (perPlate) {
                            Text(S.tickHint, color = Kc.ink2, fontSize = 11.sp)
                            platesOf(b, ticking).groupBy { it.variety }.forEach { (variety, plates) ->
                                PlateGroupHeader(
                                    name = variety,
                                    left = plates.count { !it.done }.toLong(),
                                    total = plates.size.toLong(),
                                    colour = colour
                                )
                                // Ticked plates sink to the bottom of their
                                // variety, so the next one to cook is always the
                                // top row — same spot on the screen, every tap.
                                plates.sortedBy { it.done }.forEach { p ->
                                    PlateTickRow(p) { onReadyPlate(p.key) }
                                }
                            }
                            // No bucket-wide ready button: a bucket is finished
                            // by finishing the plates in it, and the server
                            // closes it on the last one. This button is the way
                            // OUT of a bucket that still needs time — it drops
                            // the lock so another colour can be started now.
                            BigAction(S.leaveBucket, Kc.lockBg, Kc.lockFg, onLeave)
                        } else if (perVariety) {
                            Text(S.tickHint, color = Kc.ink2, fontSize = 11.sp)
                            b.lines.groupBy { it.menuItemId to it.name.ifEmpty { b.name } }
                                .forEach { (key, ls) ->
                                    VarietyTickRow(
                                        name = key.second,
                                        lines = ls,
                                        done = ls.all { it.done },
                                        onTick = { onReadyVariety(key.first) }
                                    )
                                }
                            BigAction(S.leaveBucket, Kc.lockBg, Kc.lockFg, onLeave)
                        } else {
                            b.lines.forEach { l ->
                                Row(
                                    Modifier.alpha(if (l.done) 0.45f else 1f),
                                    horizontalArrangement = Arrangement.spacedBy(9.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(l.tableName, color = Kc.ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.width(34.dp))
                                    Text("${l.qty}×", color = Kc.ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                                    // Per-line variety name: a colour bucket clubs
                                    // every same-colour bhaji, so lines can differ.
                                    Text(
                                        l.name.ifEmpty { b.name }, color = Kc.ink, fontSize = 13.sp,
                                        textDecoration = if (l.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (l.done) {
                                        Text("✓", color = Kc.ready, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                                    } else if (l.note.isNotEmpty()) {
                                        Text("⚠ ${l.note}", color = Kc.note, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            when {
                                mine -> BigAction(S.bucketReady(b.qty), Kc.ready, Kc.readyFg, onReady)
                                lockedByOther -> Text(
                                    S.cookingThis(b.takenByName),
                                    color = Kc.lockFg, fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                myBusy -> Text(
                                    S.oneBucketRule,
                                    color = Kc.lockFg, fontSize = 12.sp,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                                else -> BigAction(S.takeBucket, Kc.take, Kc.takeFg, onTake)
                            }
                        }
                    }
                }
            }
        }
    }
}

// One plate on the tawa. A ticket line of 4 is four of these, all pointing at
// the same lineId — the server splits that line one plate at a time, so ticking
// any of them sends exactly one plate to the pass.
private data class Plate(
    val lineId: Long, val table: String, val variety: String,
    val note: String, val done: Boolean, val key: String
)

// Plates that look the same to the chef (same bucket, table, dish and note)
// are interchangeable: a tap means "one of these is done", and the queue
// picks whichever line still holds one when it is that tap's turn.
private fun plateKey(b: BucketDto, l: BucketLineDto): String =
    "${b.id}|${l.tableName}|${l.menuItemId}|${l.name.ifEmpty { b.name }}|${l.note}"

// `ticking` = taps still on their way to the server; that many cooking
// plates of each key already show as done.
private fun platesOf(b: BucketDto, ticking: Map<String, Int>): List<Plate> {
    val owed = HashMap(ticking)
    return b.lines.flatMap { l ->
        val variety = l.name.ifEmpty { b.name }
        val key = plateKey(b, l)
        List(l.qty.toInt().coerceAtLeast(1)) {
            val tickedNow = !l.done && (owed[key] ?: 0) > 0
            if (tickedNow) owed[key] = owed.getValue(key) - 1
            Plate(l.id, l.tableName, variety, l.note, l.done || tickedNow, key)
        }
    }
}

// Which fetch of the kitchen started last / is on screen (see refresh()).
private class FetchOrder { var started = 0; var applied = 0 }

// The heading over a variety's plates — not a button, just the count that tells
// the chef how much of this bhaji is still on the pan.
@Composable
private fun PlateGroupHeader(name: String, left: Long, total: Long, colour: Color) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Box(Modifier.size(9.dp).background(colour, RoundedCornerShape(3.dp)))
        Text(
            name, color = Kc.ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
        )
        Text(
            if (left == 0L) S.platesAllOut else S.plateLeft(left, total),
            color = if (left == 0L) Kc.ready else Kc.ink2,
            fontSize = 11.5.sp, fontWeight = FontWeight.Bold
        )
    }
}

// One plate, one row, one tap. The WHOLE row is the button; a ticked plate stays
// on screen struck through with a solid green ✓ so the chef can see their tap
// landed, and the bucket closes itself when the last one goes out. The buzz is
// honest: a row only takes a tap while it is still cooking, and every such
// tap is queued for the server — none is dropped behind a busy flag.
@Composable
private fun PlateTickRow(p: Plate, onTick: () -> Unit) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && !p.done) 0.97f else 1f,
        spring(dampingRatio = 0.65f, stiffness = 1100f), label = "plate"
    )
    Surface(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onTick()
        },
        enabled = !p.done,
        interactionSource = interaction,
        color = if (p.done) Kc.inside else Kc.card,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, if (p.done) Kc.border else Kc.ink2.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 9.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Which table this one plate belongs to — the only thing that tells
            // two otherwise identical plates apart.
            Text(
                p.table, color = if (p.done) Kc.ink2 else Kc.ink,
                fontSize = 14.sp, fontWeight = FontWeight.ExtraBold,
                maxLines = 1, modifier = Modifier.width(40.dp)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    p.variety, color = if (p.done) Kc.ink2 else Kc.ink,
                    fontSize = 14.5.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textDecoration = if (p.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                )
                if (p.done) {
                    Text(S.platePass, color = Kc.ready, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                } else if (p.note.isNotEmpty()) {
                    Text("⚠ ${p.note}", color = Kc.note, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Box(
                Modifier
                    .size(38.dp)
                    .background(if (p.done) Kc.ready else Color.Transparent, CircleShape)
                    .border(
                        2.5.dp,
                        if (p.done) Kc.ready else Kc.ink2.copy(alpha = 0.75f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (p.done) {
                    Text("✓", color = Kc.readyFg, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

// One bhaji of a bucket: 3× Cheese and 4× Regular share a tawa but rarely
// finish together, so each gets its own big row — the WHOLE row is the button,
// and one tap sends that bhaji to the pass immediately. Done rows stay put,
// struck through with a solid green ✓, until the last one closes the bucket.
@Composable
private fun VarietyTickRow(
    name: String, lines: List<BucketLineDto>, done: Boolean,
    onTick: () -> Unit
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && !done) 0.97f else 1f,
        spring(dampingRatio = 0.65f, stiffness = 1100f), label = "tick"
    )
    Surface(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onTick()
        },
        enabled = !done,
        interactionSource = interaction,
        color = if (done) Kc.inside else Kc.card,
        shape = RoundedCornerShape(13.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, if (done) Kc.border else Kc.ink2.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
    ) {
        Row(
            Modifier.padding(horizontal = 13.dp, vertical = 12.dp).heightIn(min = 46.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "${lines.sumOf { it.qty }}× $name",
                    color = if (done) Kc.ink2 else Kc.ink,
                    fontSize = 17.sp, fontWeight = FontWeight.ExtraBold,
                    textDecoration = if (done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null
                )
                Text(
                    if (done) S.donePlates else lines.joinToString("   ") { "${it.tableName} ×${it.qty}" },
                    color = if (done) Kc.ready else Kc.ink2,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                )
                if (!done) {
                    lines.filter { it.note.isNotEmpty() }.forEach { l ->
                        Text(
                            "⚠ ${l.tableName}: ${l.note}",
                            color = Kc.note, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            // The tick itself: hollow ring while cooking, solid green once out.
            Box(
                Modifier
                    .size(42.dp)
                    .background(if (done) Kc.ready else Color.Transparent, CircleShape)
                    .border(
                        2.5.dp,
                        if (done) Kc.ready else Kc.ink2.copy(alpha = 0.75f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (done) {
                    Text("✓", color = Kc.readyFg, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }
}

// Take / Ready: the two buttons a chef hits with wet hands all day — big,
// springy, and clicky so a press is unmistakable even mid-rush.
@Composable
private fun BigAction(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.965f else 1f,
        spring(dampingRatio = 0.65f, stiffness = 1100f),
        label = "bigAction"
    )
    Surface(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        },
        interactionSource = interaction,
        color = bg, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
    ) {
        Text(
            label, color = fg, fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp)
        )
    }
}
