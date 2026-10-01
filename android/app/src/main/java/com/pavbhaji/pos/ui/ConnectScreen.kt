package com.pavbhaji.pos.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.pavbhaji.pos.Deps
import com.pavbhaji.pos.data.Discovery
import com.pavbhaji.pos.data.Session
import kotlinx.coroutines.launch

// Server setup. A new phone needs none of it (the cloud server is built in),
// so the login screen hides the way in behind a long press on the logo. It
// tries the cloud server first, then the old LAN hunt (UDP shout, subnet
// sweep) in case a counter machine ever comes back. Fallbacks: scan a QR,
// tap the right server if several answered, or type the address.
@Composable
fun ConnectScreen(onConnected: () -> Unit) {
    val scope = rememberCoroutineScope()
    var addr by remember { mutableStateOf(Deps.session.serverAddr) }
    var busy by remember { mutableStateOf(false) } // verifying one address
    var searching by remember { mutableStateOf(true) } // hunt in progress
    var searchedOnce by remember { mutableStateOf(false) }
    var candidates by remember { mutableStateOf(listOf<Discovery.Found>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var searchKey by remember { mutableIntStateOf(0) }
    StatusBarIcons(darkIcons = true)

    fun accept(f: Discovery.Found) {
        Deps.session.serverAddr = f.addr
        if (f.id.isNotEmpty()) Deps.session.serverId = f.id
        onConnected()
    }

    fun tryAddr(raw: String) {
        val target = normalizeAddr(raw)
        if (target == null) {
            error = S.badAddress
            return
        }
        busy = true; error = null
        scope.launch {
            val f = Discovery.verify(target)
            busy = false
            if (f != null) accept(f)
            else error = S.noServerAt(target)
        }
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { tryAddr(it) }
    }

    LaunchedEffect(searchKey) {
        searching = true; error = null
        // The shop server lives on the internet now, so it is the first thing
        // to try; a LAN sweep only matters if somebody ever puts a machine back
        // on the counter.
        val cloud = Discovery.verify(Session.CLOUD_SERVER)
        val lastPort = Deps.session.serverAddr.substringAfterLast(':', "").toIntOrNull()
        val found = (listOfNotNull(cloud) + Discovery.search(listOfNotNull(lastPort)))
            .distinctBy { it.addr }
        candidates = found
        searching = false
        searchedOnce = true
        if (found.size == 1) tryAddr(found[0].addr)
    }

    Box(Modifier.fillMaxSize().background(Cream)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier
                    .size(84.dp)
                    .background(
                        Brush.linearGradient(listOf(Color(0xFFFFE3C2), Color(0xFFFFD1A6))),
                        RoundedCornerShape(26.dp)
                    ),
                contentAlignment = Alignment.Center
            ) { Text("🍛", fontSize = 46.sp) }
            Text(
                "PavBhaji POS",
                fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp,
                modifier = Modifier.padding(top = 14.dp)
            )
            Spacer(Modifier.height(18.dp))

            when {
                searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        "  " + S.searchingLaptop,
                        fontSize = 14.sp,
                        color = Pb.ink2
                    )
                }

                candidates.size > 1 -> {
                    Text(
                        S.foundServers(candidates.size),
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                    )
                    candidates.forEach { f ->
                        PbCard(
                            onClick = { if (!busy) tryAddr(f.addr) },
                            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("🖥", fontSize = 20.sp)
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(f.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    Text(f.addr, fontSize = 12.sp, color = Pb.ink3)
                                }
                                Text("→", fontSize = 18.sp, color = BhajiRed, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                searchedOnce -> Text(
                    S.notFound,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp,
                    color = Pb.ink2
                )
            }

            if (error != null) {
                Text(
                    error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp)
                )
            }

            Spacer(Modifier.height(22.dp))
            BigButton(
                label = S.scanQr,
                loading = busy,
                loadingLabel = S.checking,
                onClick = {
                    scanLauncher.launch(
                        ScanOptions()
                            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            .setPrompt(S.scanPrompt)
                            .setBeepEnabled(false)
                            .setOrientationLocked(true)
                            .setCaptureActivity(PortraitCaptureActivity::class.java)
                    )
                }
            )
            TextButton(
                onClick = { searchKey++ }, enabled = !searching && !busy,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(S.searchAgain, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }

            Spacer(Modifier.height(14.dp))
            Text(
                S.orTypeAddr,
                fontSize = 12.sp,
                color = Pb.ink3
            )
            OutlinedTextField(
                value = addr,
                onValueChange = { addr = it.trim(); error = null },
                label = { Text(S.serverAddress) },
                placeholder = { Text(Session.CLOUD_SERVER) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            )
            BigButton(
                label = S.connect,
                bg = Color.Transparent,
                fg = BhajiRed,
                border = androidx.compose.foundation.BorderStroke(1.5.dp, BhajiRed.copy(alpha = 0.5f)),
                enabled = addr.length >= 7,
                loading = busy,
                loadingLabel = S.checking,
                onClick = { tryAddr(addr) },
                modifier = Modifier.padding(top = 10.dp)
            )
        }
        LangAction(
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()
        )
    }
}

// Accepts "192.168.1.50:8177", bare "192.168.1.50" (default port assumed),
// or scanned/link forms like "http://192.168.1.50:8177/".
private fun normalizeAddr(raw: String): String? {
    var a = raw.trim().removePrefix("pbpos://")
    // A full URL is the shop server itself (https, no port) — keep it whole.
    if (a.startsWith("http://") || a.startsWith("https://")) {
        val u = a.trimEnd('/')
        return if (u.substringAfter("://").isEmpty()) null else u
    }
    a = a.substringBefore('/').trim()
    if (a.isEmpty()) return null
    if (!a.contains(':')) a = "$a:${Discovery.DEFAULT_PORT}"
    return a
}
