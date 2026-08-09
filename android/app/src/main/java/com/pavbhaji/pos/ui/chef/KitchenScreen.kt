package com.pavbhaji.pos.ui.chef

import android.app.Activity
import android.media.AudioManager
import android.media.ToneGenerator
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pavbhaji.pos.Deps
import com.pavbhaji.pos.data.ApiResult
import com.pavbhaji.pos.data.BucketDto
import com.pavbhaji.pos.data.KitchenDto
import com.pavbhaji.pos.ui.ConnBanner
import com.pavbhaji.pos.ui.Kc
import com.pavbhaji.pos.ui.S
import com.pavbhaji.pos.ui.dishColour
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The chef's screen is a queue of colour buckets, oldest first.
// Tap a bucket to open its lines; "Take" locks it to this chef;
// "Bucket ready" flips every plate inside to READY in one go.
@Composable
fun KitchenScreen(onLogout: () -> Unit, embedded: Boolean = false, onBack: (() -> Unit)? = null) {
    val scope = rememberCoroutineScope()
    val connected by Deps.live.connected.collectAsState()
    var data by remember { mutableStateOf(KitchenDto()) }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val myId = Deps.session.staffId

    // Kitchen phone should never dim mid-rush.
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // Beep when a bucket we've never seen shows up.
    val seenBuckets = remember { mutableSetOf<Long>() }
    var firstLoad by remember { mutableStateOf(true) }

    suspend fun refresh() {
        when (val r = Deps.api.kitchen()) {
            is ApiResult.Ok -> {
                val fresh = r.value.buckets.map { it.id }.filter { it !in seenBuckets }
                if (fresh.isNotEmpty() && !firstLoad && !embedded) {
                    try {
                        ToneGenerator(AudioManager.STREAM_MUSIC, 90)
                            .startTone(ToneGenerator.TONE_PROP_BEEP2, 500)
                    } catch (_: Exception) {}
                }
                seenBuckets.addAll(fresh)
                firstLoad = false
                data = r.value
                // My cooking bucket stays open in front of me.
                if (expandedId == null) {
                    expandedId = r.value.buckets.firstOrNull { it.status == "taken" && it.takenBy == myId }?.id
                }
            }
            is ApiResult.Err -> {}
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

    if (onBack != null) BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().background(Kc.bg)) {
        // Dark header — the brand red bar lives on the login screen only.
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .height(56.dp)
                .padding(horizontal = 6.dp),
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
            Box(Modifier.size(7.dp).background(if (connected) Kc.ready else Kc.late, CircleShape))
            if (onBack == null) {
                TextButton(onClick = onLogout) {
                    Text(S.logout, color = Kc.ink2, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            } else {
                Spacer(Modifier.width(10.dp))
            }
        }
        ConnBanner(connected)
        toast?.let {
            Text(
                it, color = Kc.late, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 3.dp)
            )
        }

        if (data.buckets.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(S.noBuckets, color = Kc.ink2, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                val myBusy = data.buckets.any { it.status == "taken" && it.takenBy == myId }
                items(data.buckets.size, key = { data.buckets[it].id }) { i ->
                    val b = data.buckets[i]
                    BucketCard(
                        b = b, cap = if (b.cap > 0) b.cap else data.cap, myId = myId, myBusy = myBusy,
                        expanded = expandedId == b.id,
                        onTap = { expandedId = if (expandedId == b.id) null else b.id },
                        onTake = {
                            if (!busy) {
                                busy = true
                                scope.launch {
                                    when (val r = Deps.api.takeBatch(b.id)) {
                                        is ApiResult.Ok -> expandedId = b.id
                                        is ApiResult.Err -> toast = r.message
                                    }
                                    refresh(); busy = false
                                }
                            }
                        },
                        onReady = {
                            if (!busy) {
                                busy = true
                                scope.launch {
                                    when (val r = Deps.api.readyBatch(b.id)) {
                                        is ApiResult.Ok -> if (expandedId == b.id) expandedId = null
                                        is ApiResult.Err -> toast = r.message
                                    }
                                    refresh(); busy = false
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
    expanded: Boolean,
    onTap: () -> Unit, onTake: () -> Unit, onReady: () -> Unit
) {
    val colour = dishColour(b.colour, b.menuItemId)
    val mine = b.status == "taken" && b.takenBy == myId
    val lockedByOther = b.status == "taken" && b.takenBy != myId

    Surface(
        color = Kc.card,
        shape = RoundedCornerShape(15.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Kc.border),
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (lockedByOther) 0.62f else 1f)
            .clip(RoundedCornerShape(15.dp))
            .clickable(onClick = onTap)
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            // dish colour band down the left edge
            Box(Modifier.width(6.dp).fillMaxHeight().background(colour))
            Column(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 11.dp, top = 11.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(Modifier.size(15.dp).background(colour, RoundedCornerShape(5.dp)))
                    Text(
                        b.name, color = Kc.ink, fontSize = 14.5.sp, fontWeight = FontWeight.ExtraBold,
                        maxLines = 1, modifier = Modifier.weight(1f, fill = false)
                    )
                    when {
                        mine -> StateChip(S.yoursChip(minsSince(b.takenAt)), Kc.mineBg, Kc.mineFg)
                        lockedByOther -> StateChip("🔒 ${b.takenByName}", Kc.lockBg, Kc.lockFg)
                        b.status == "full" -> StateChip(S.fullChip, Kc.fullBg, Kc.fullFg)
                        else -> StateChip(S.fillingChip, Color.Transparent, Kc.ink2, dashed = true)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${b.qty}", color = Kc.ink, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold
                    )
                    Text("/$cap", color = Kc.ink2, fontSize = 11.sp)
                    if (b.status != "taken") AgeChip(b.createdAt)
                }
                // fill bar
                Row(
                    Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        Modifier.weight(1f).height(5.dp)
                            .background(Kc.border, RoundedCornerShape(99.dp))
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth((b.qty.toFloat() / cap).coerceIn(0f, 1f))
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
                        Text(hint, color = Kc.ink2, fontSize = 10.5.sp)
                    }
                }

                if (expanded) {
                    Column(
                        Modifier.fillMaxWidth().background(Kc.inside)
                            .padding(start = 10.dp, end = 12.dp, top = 9.dp, bottom = 11.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        b.lines.forEach { l ->
                            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(l.tableName, color = Kc.ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.width(34.dp))
                                Text("${l.qty}×", color = Kc.ink, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                                Text(b.name, color = Kc.ink, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false))
                                if (l.note.isNotEmpty()) {
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

@Composable
private fun BigAction(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = bg, shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) {
        Text(
            label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(vertical = 10.dp)
        )
    }
}
