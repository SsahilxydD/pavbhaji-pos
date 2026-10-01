package com.pavbhaji.pos.ui

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first

// The one money formatter (server amounts are paise). Indian grouping, the
// way the shop writes it — ₹13,660 · ₹1,23,456 — and paise only when there
// are some: ₹120.50. The web screens print exactly the same.
fun rupees(paise: Long): String {
    val p = kotlin.math.abs(paise)
    val frac = p % 100
    val s = "₹" + indianDigits(p / 100) +
        if (frac == 0L) "" else "." + frac.toString().padStart(2, '0')
    return if (paise < 0) "-$s" else s
}

// 1234567 → "12,34,567": the last three digits, then pairs.
fun indianDigits(n: Long): String {
    val s = n.toString()
    if (s.length <= 3) return s
    val head = s.dropLast(3)
    val pairs = head.reversed().chunked(2).joinToString(",").reversed()
    return "$pairs,${s.takeLast(3)}"
}

// Money stays with money-facing roles: chef and waiter screens never show
// prices. Admin sees them everywhere (including the Floor view, which reuses
// waiter screens); the cashier's whole job is the bill; the parcel manager
// collects payment on handover.
fun pricesVisible(): Boolean =
    com.pavbhaji.pos.Deps.session.staffRole in setOf("admin", "cashier", "parcel")

fun items(n: Long): String = S.itemsN(n)

fun ageMinutes(sinceMs: Long): String {
    val m = ((System.currentTimeMillis() - sinceMs) / 60000).coerceAtLeast(0)
    return when {
        m < 1 -> S.now
        m < 60 -> "${m}m"
        else -> "${m / 60}h${m % 60}m"
    }
}

fun clockTime(ms: Long): String {
    val cal = java.util.Calendar.getInstance()
    cal.timeInMillis = ms
    return "%02d:%02d".format(cal.get(java.util.Calendar.HOUR_OF_DAY), cal.get(java.util.Calendar.MINUTE))
}

// While `active`, the silent self-update waits for the next time the phone
// is put down (AppUpdater.applyStaged): an unsent cart or a bill being
// settled must not vanish because the screen timed out mid-order.
@Composable
fun HoldUpdates(active: Boolean) {
    val key = remember { Any() }
    DisposableEffect(active) {
        com.pavbhaji.pos.data.AppUpdater.hold(key, active)
        onDispose { com.pavbhaji.pos.data.AppUpdater.hold(key, false) }
    }
}

// Each screen decides its status-bar icon tint: the app is edge-to-edge, so
// icons sit on whatever the screen paints up there (red header, dark kitchen,
// or plain cream). darkIcons=true on cream, false on red/dark grounds.
@Composable
fun StatusBarIcons(darkIcons: Boolean) {
    val view = LocalView.current
    LaunchedEffect(darkIcons) {
        val window = (view.context as? android.app.Activity)?.window ?: return@LaunchedEffect
        androidx.core.view.WindowCompat.getInsetsController(window, view)
            .isAppearanceLightStatusBars = darkIcons
    }
}

// The one header used by every screen. Gives the app its identity (deep red
// gradient), a 48dp back button that's easy to hit, and — crucially — wires the
// system back gesture/button to the same onBack, so "swipe back" always works.
// min-height, not fixed height: large system fonts must grow the bar, not clip.
@Composable
fun AppHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    if (onBack != null) BackHandler(onBack = onBack)
    StatusBarIcons(darkIcons = false)
    Column {
    Box(
        Modifier.background(
            Brush.linearGradient(listOf(Pb.headerTop, Pb.headerBottom))
        )
    ) {
        // safeDrawing (status bar ∪ display cutout) instead of just the status
        // bar: on notch/full-screen phones the cutout can be taller than the
        // reported status bar, and the bar's content must start below BOTH.
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)
                )
                .heightIn(min = 56.dp)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            } else {
                Text("🍛", fontSize = 21.sp, modifier = Modifier.padding(start = 12.dp, end = 4.dp))
            }
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Text(
                    title, color = Color.White, fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.3).sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        subtitle, color = Pb.headerSub, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            LangAction()
            actions()
        }
    }
    // Every screen with this header gets the one offline banner under it —
    // no screen can forget it, and none shows it twice.
    val connected by com.pavbhaji.pos.Deps.live.connected.collectAsState()
    ConnBanner(connected)
    }
}

// Compact language switcher living in every screen's top-right corner:
// shows the current language's own short mark, opens the three full names.
@Composable
fun LangAction(tint: Color = Color.White, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        TextButton(onClick = { open = true }) {
            Text(
                when (L10n.lang) { "hi" -> "हिं"; "gu" -> "ગુ"; else -> "EN" },
                color = tint, fontWeight = FontWeight.Bold, fontSize = 14.sp
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf("en" to "English", "hi" to "हिंदी", "gu" to "ગુજરાતી").forEach { (code, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            label,
                            fontWeight = if (L10n.lang == code) FontWeight.ExtraBold else FontWeight.Normal
                        )
                    },
                    onClick = {
                        L10n.lang = code
                        com.pavbhaji.pos.Deps.session.lang = code
                        open = false
                    }
                )
            }
        }
    }
}

@Composable
fun HeaderTextAction(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
    }
}

// Logout sits in every header right next to the language switch, and one
// stray tap used to drop the session mid-shift. It asks first; the safe
// choice ("Stay") is the dismiss.
@Composable
fun LogoutAction(onLogout: () -> Unit, tint: Color = Color.White, fontSize: Int = 14) {
    var ask by remember { mutableStateOf(false) }
    TextButton(onClick = { ask = true }) {
        Text(S.logout, color = tint, fontWeight = FontWeight.Bold, fontSize = fontSize.sp)
    }
    if (ask) {
        ConfirmDialog(
            title = S.logoutQ(com.pavbhaji.pos.Deps.session.staffName),
            body = S.logoutBody,
            confirmLabel = S.logout,
            dismissLabel = S.stayBtn,
            onConfirm = { ask = false; onLogout() },
            onDismiss = { ask = false }
        )
    }
}

// Slides in under the header while the WebSocket is down. Amber, not red:
// it's "hold on", not "something you did failed". It waits a moment before
// showing, so the half-second dial at app start (or one dropped packet)
// doesn't flash "no internet" at a waiter; it goes away on its own the
// moment the line is back.
@Composable
fun ConnBanner(connected: Boolean) {
    var down by remember { mutableStateOf(false) }
    LaunchedEffect(connected) {
        if (connected) down = false else { kotlinx.coroutines.delay(1500); down = true }
    }
    AnimatedVisibility(
        visible = down,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFF7E7C8))
                .padding(vertical = 7.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val pulse = rememberInfiniteTransition(label = "conn")
            val a by pulse.animateFloat(
                0.35f, 1f,
                infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "connA"
            )
            Box(Modifier.size(8.dp).alpha(a).background(WarnAmber, CircleShape))
            Text(
                S.reconnecting,
                color = Color(0xFF6B4A00), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 8.dp, end = 12.dp)
            )
        }
    }
}

// One-line error strip for surfaced API failures — screens keep working, the
// user just sees why the data may be stale. Pass null to hide.
// While the offline banner is up it already says "no internet"; a red strip
// under it saying the same thing again is noise, so that one case is hidden.
@Composable
fun ErrorBanner(message: String?, dark: Boolean = false) {
    val connected by com.pavbhaji.pos.Deps.live.connected.collectAsState()
    val shown = message?.takeUnless { !connected && it in S.noNetworkAll }
    AnimatedVisibility(
        visible = shown != null,
        enter = expandVertically() + fadeIn(),
        exit = shrinkVertically() + fadeOut()
    ) {
        var last by remember { mutableStateOf(shown ?: "") }
        if (shown != null) last = shown
        val bg = if (dark) Color(0xFF3A1712) else Pb.dangerBg
        val fg = if (dark) Color(0xFFFFB59E) else Pb.danger
        Row(
            Modifier.fillMaxWidth().background(bg).padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("⚠", fontSize = 12.sp, color = fg)
            Text(
                last, color = fg, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 7.dp)
            )
        }
    }
}

@Composable
fun StatusChip(status: String) {
    val (bg, fg) = when (status) {
        "pending" -> Color(0xFFFFECB3) to Color(0xFF5D4000)
        "preparing" -> Color(0xFFFFD180) to Color(0xFF7A3E00)
        "ready" -> Color(0xFFC8E6C9) to Color(0xFF1B5E20)
        "served" -> Color(0xFFE0E0E0) to Color(0xFF424242)
        "cancelled" -> Color(0xFFFFCDD2) to Color(0xFF8E0000)
        else -> Color.LightGray to Color.DarkGray
    }
    Surface(color = bg, shape = RoundedCornerShape(6.dp)) {
        Text(
            S.statusLabel(status), color = fg, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

// Small rounded status pill, shared by every light screen.
@Composable
fun Pill(text: String, bg: Color, fg: Color) {
    Surface(color = bg, shape = RoundedCornerShape(99.dp)) {
        Text(
            text, color = fg, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

// The standard content card: soft corner, hairline border, gentle shadow.
// With onClick it also gets the press-shrink that makes taps feel physical.
@Composable
fun PbCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surface,
    border: BorderStroke? = BorderStroke(1.dp, CardLine),
    corner: Dp = 16.dp,
    elevation: Dp = 1.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    if (onClick == null) {
        Surface(
            modifier = modifier, color = color, border = border,
            shape = RoundedCornerShape(corner), shadowElevation = elevation,
            content = content
        )
    } else {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val scale by animateFloatAsState(
            if (pressed) 0.975f else 1f,
            spring(dampingRatio = 0.75f, stiffness = 900f), label = "cardPress"
        )
        Surface(
            onClick = onClick, interactionSource = interaction,
            modifier = modifier.graphicsLayer { scaleX = scale; scaleY = scale },
            color = color, border = border,
            shape = RoundedCornerShape(corner), shadowElevation = elevation,
            content = content
        )
    }
}

// Full-width primary action. Press-shrink + key-click haptic + built-in
// loading state so screens stop hand-rolling "Sending…" buttons.
@Composable
fun BigButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bg: Color = BhajiRed,
    fg: Color = Color.White,
    border: BorderStroke? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    fontSize: Int = 15
) {
    val view = LocalView.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && enabled && !loading) 0.965f else 1f,
        spring(dampingRatio = 0.7f, stiffness = 900f), label = "btnPress"
    )
    Surface(
        onClick = {
            if (enabled && !loading) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onClick()
            }
        },
        enabled = enabled && !loading,
        interactionSource = interaction,
        color = if (enabled) bg else bg.copy(alpha = 0.45f),
        border = border,
        shape = RoundedCornerShape(14.dp),
        modifier = modifier.fillMaxWidth().graphicsLayer { scaleX = scale; scaleY = scale }
    ) {
        Box(Modifier.heightIn(min = 52.dp).padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            val fgNow = if (enabled) fg else fg.copy(alpha = 0.55f)
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = fgNow)
                    Text(
                        S.sending, color = fgNow, fontSize = fontSize.sp, fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            } else {
                Text(
                    label, color = fgNow, fontSize = fontSize.sp, fontWeight = FontWeight.ExtraBold,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// Soft pulsing placeholder block — building brick of every loading state.
@Composable
fun Skeleton(modifier: Modifier = Modifier, corner: Dp = 10.dp, base: Color = Pb.skeleton) {
    val t = rememberInfiniteTransition(label = "skel")
    val a by t.animateFloat(
        0.45f, 1f,
        infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "skelA"
    )
    Box(modifier.alpha(a).background(base, RoundedCornerShape(corner)))
}

// First-load placeholder: a stack of card-shaped shimmers instead of a blank
// screen popping into content.
@Composable
fun ListSkeleton(rows: Int = 4, rowHeight: Dp = 74.dp, base: Color = Pb.skeleton) {
    Column(
        Modifier.fillMaxWidth().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        repeat(rows) {
            Skeleton(Modifier.fillMaxWidth().height(rowHeight), corner = 16.dp, base = base)
        }
    }
}

// Friendly full-area empty state: big emoji, calm words.
@Composable
fun EmptyState(emoji: String, text: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(emoji, fontSize = 44.sp)
        Text(
            text,
            color = Pb.ink2, fontSize = 14.sp, textAlign = TextAlign.Center,
            lineHeight = 20.sp,
            modifier = Modifier.padding(top = 10.dp).widthIn(max = 280.dp)
        )
    }
}

@Composable
fun EmptyHint(text: String) = EmptyState("🍽️", text)

// Work screens keep their DONE pile above the LIVE part of the list, so
// finished rows drift up out of sight instead of shoving the buttons around.
// When the pile changes size this re-pins the first live item to the top of
// the viewport — but only if the user is parked right at the pin. Anywhere
// else (up re-reading the pile, or down inside a long menu) they're left
// alone: every row is keyed, so LazyColumn already holds the visible rows
// still while items are inserted or removed above them.
// A LazyColumn can only park an item at the top if at least a viewport of
// content exists below it, so every caller must also give the list a tail
// filler (see the screens' `filler` spacers).
@Composable
fun PinBelowHistory(listState: LazyListState, boundary: Int) {
    val target by rememberUpdatedState(boundary)
    var pinned by remember { mutableIntStateOf(-1) }
    // First pin: wait until the list has really laid out (a scroll issued
    // before that races the first layout and can silently stay at the top),
    // then jump past whatever history came with the initial load.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it > 0 }
        if (listState.firstVisibleItemIndex <= target) listState.scrollToItem(target)
        pinned = target
    }
    // Later pins: when the pile above changes size and the user is parked
    // right at the pin, follow it.
    LaunchedEffect(boundary) {
        if (pinned == -1 || boundary == pinned) return@LaunchedEffect
        if (listState.firstVisibleItemIndex == pinned) listState.scrollToItem(boundary)
        pinned = boundary
    }
}

// 4-digit PIN pad. Submits by itself on the 4th digit — no OK button.
// Width adapts to the phone (no fixed 88dp buttons that clip small screens);
// dots pop as they fill; the whole pad shakes sideways on a wrong PIN.
@Composable
fun PinPad(onSubmit: (String) -> Unit, error: String?) {
    var pin by remember { mutableStateOf("") }
    val view = LocalView.current
    val shake = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(error) {
        if (error != null) {
            pin = ""
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            listOf(18f, -14f, 9f, -5f, 0f).forEach {
                shake.animateTo(it, spring(stiffness = 4000f))
            }
        }
    }

    Column(
        Modifier.widthIn(max = 330.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.graphicsLayer { translationX = shake.value }
        ) {
            repeat(4) { i ->
                val filled = i < pin.length
                val dotScale by animateFloatAsState(
                    if (filled) 1.15f else 1f,
                    spring(dampingRatio = 0.5f, stiffness = 1400f), label = "dot$i"
                )
                Box(
                    Modifier
                        .size(16.dp)
                        .graphicsLayer { scaleX = dotScale; scaleY = dotScale }
                        .background(
                            if (filled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        )
                )
            }
        }
        Text(
            error ?: " ",
            color = MaterialTheme.colorScheme.error, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp)
        )
        Column(
            Modifier.padding(top = 8.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val rows = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "⌫"))
            rows.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    row.forEach { key ->
                        if (key.isEmpty()) {
                            Box(Modifier.weight(1f).heightIn(min = 60.dp))
                        } else {
                            val interaction = remember { MutableInteractionSource() }
                            val pressed by interaction.collectIsPressedAsState()
                            val scale by animateFloatAsState(
                                if (pressed) 0.93f else 1f,
                                spring(dampingRatio = 0.6f, stiffness = 1200f), label = "key$key"
                            )
                            Surface(
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    if (key == "⌫") {
                                        if (pin.isNotEmpty()) pin = pin.dropLast(1)
                                    } else if (pin.length < 4) {
                                        pin += key
                                        if (pin.length == 4) {
                                            val p = pin
                                            onSubmit(p)
                                        }
                                    }
                                },
                                interactionSource = interaction,
                                shape = RoundedCornerShape(16.dp),
                                color = MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, CardLine),
                                shadowElevation = 1.dp,
                                modifier = Modifier
                                    .weight(1f)
                                    .graphicsLayer { scaleX = scale; scaleY = scale }
                            ) {
                                Box(
                                    Modifier.heightIn(min = 60.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        key, fontSize = 23.sp, fontWeight = FontWeight.Bold,
                                        color = if (key == "⌫") Pb.ink2 else InkDark
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReasonDialog(title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(S.reasonRequired, fontSize = 13.sp)
                OutlinedTextField(
                    value = reason, onValueChange = { reason = it },
                    label = { Text(S.reason) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (reason.trim().length >= 2) onConfirm(reason.trim()) },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) { Text(S.cancelItem, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(S.back) } }
    )
}

// destructive = the confirm button throws something away: it turns red, and
// the dismiss label should name the safe choice ("Keep"), not just "Back".
@Composable
fun ConfirmDialog(
    title: String, body: String, confirmLabel: String,
    onConfirm: () -> Unit, onDismiss: () -> Unit,
    dismissLabel: String = S.back, destructive: Boolean = false
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(22.dp),
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = { Text(body, fontSize = 15.sp) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                shape = RoundedCornerShape(12.dp),
                colors = if (destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                else ButtonDefaults.buttonColors()
            ) { Text(confirmLabel, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } }
    )
}

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier, accent: Color = BhajiRed) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, CardLine),
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).background(accent, CircleShape))
                Text(
                    label, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp,
                    color = Pb.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                value, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, color = InkDark,
                letterSpacing = (-0.4).sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
fun QtyStepper(qty: Long, onChange: (Long) -> Unit) {
    val view = LocalView.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onChange(qty - 1)
            },
            modifier = Modifier.size(42.dp),
            shape = CircleShape,
            border = BorderStroke(1.dp, CardLine),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
        ) { Text("−", fontSize = 20.sp, color = InkDark) }
        Text(
            qty.toString(),
            fontSize = 18.sp, fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.widthIn(min = 34.dp),
            textAlign = TextAlign.Center
        )
        OutlinedButton(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onChange(qty + 1)
            },
            modifier = Modifier.size(42.dp),
            shape = CircleShape,
            border = BorderStroke(1.dp, CardLine),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
        ) { Text("+", fontSize = 20.sp, color = BhajiRed) }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 12.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 1.2.sp,
        color = Pb.ink3,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
    )
}
