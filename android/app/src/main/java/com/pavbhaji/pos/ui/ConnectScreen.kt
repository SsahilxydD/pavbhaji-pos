package com.pavbhaji.pos.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.pavbhaji.pos.Deps
import com.pavbhaji.pos.data.Discovery
import kotlinx.coroutines.launch

// Connecting a phone should be zero work: the screen hunts for the laptop by
// itself (UDP shout, then subnet sweep) and connects when it finds exactly one.
// Fallbacks, in order of effort: scan the QR on the laptop screen, tap the
// right server if several answered, or type the address like the old days.
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
        val lastPort = Deps.session.serverAddr.substringAfterLast(':', "").toIntOrNull()
        val found = Discovery.search(listOfNotNull(lastPort))
        candidates = found
        searching = false
        searchedOnce = true
        if (found.size == 1) tryAddr(found[0].addr)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🍛", fontSize = 52.sp)
        Text("PavBhaji POS", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(18.dp))

        when {
            searching -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    "  " + S.searchingLaptop,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            candidates.size > 1 -> {
                Text(
                    S.foundServers(candidates.size),
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
                candidates.forEach { f ->
                    OutlinedButton(
                        onClick = { tryAddr(f.addr) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    ) { Text("${f.name}  ·  ${f.addr}") }
                }
            }

            searchedOnce -> Text(
                S.notFound,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (error != null) {
            Text(
                error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp)
            )
        }

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                scanLauncher.launch(
                    ScanOptions()
                        .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        .setPrompt(S.scanPrompt)
                        .setBeepEnabled(false)
                        .setOrientationLocked(true)
                        .setCaptureActivity(PortraitCaptureActivity::class.java)
                )
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (busy) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
            else Text(S.scanQr, fontSize = 16.sp)
        }
        TextButton(onClick = { searchKey++ }, enabled = !searching && !busy) {
            Text(S.searchAgain, fontSize = 13.sp)
        }

        Spacer(Modifier.height(14.dp))
        Text(
            S.orTypeAddr,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = addr,
            onValueChange = { addr = it.trim(); error = null },
            label = { Text(S.serverAddress) },
            placeholder = { Text("192.168.1.50:8177") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        )
        OutlinedButton(
            onClick = { tryAddr(addr) },
            enabled = !busy && addr.length >= 7,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
        ) { Text(S.connect, fontSize = 15.sp) }
    }
}

// Accepts "192.168.1.50:8177", bare "192.168.1.50" (default port assumed),
// or scanned/link forms like "http://192.168.1.50:8177/".
private fun normalizeAddr(raw: String): String? {
    var a = raw.trim()
        .removePrefix("pbpos://")
        .removePrefix("http://")
        .removePrefix("https://")
        .substringBefore('/')
        .trim()
    if (a.isEmpty()) return null
    if (!a.contains(':')) a = "$a:${Discovery.DEFAULT_PORT}"
    return a
}
