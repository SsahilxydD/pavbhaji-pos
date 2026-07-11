package com.pavbhaji.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pavbhaji.pos.Deps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

@Composable
fun ConnectScreen(onConnected: () -> Unit) {
    var addr by remember { mutableStateOf(Deps.session.serverAddr) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🍛", fontSize = 52.sp)
        Text("PavBhaji POS", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            "Type the address shown on the laptop screen",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp, bottom = 18.dp)
        )
        OutlinedTextField(
            value = addr,
            onValueChange = { addr = it.trim(); error = null },
            label = { Text("Server address") },
            placeholder = { Text("192.168.1.50:8177") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth()
        )
        if (error != null) {
            Text(error!!, color = MaterialTheme.colorScheme.error, fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp))
        }
        Button(
            onClick = {
                busy = true; error = null
                val target = if (addr.contains(":")) addr else "$addr:8177"
                scope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        try {
                            Deps.api.http.newCall(
                                Request.Builder().url("http://$target/").get().build()
                            ).execute().use { r ->
                                r.isSuccessful && (r.body?.string()?.contains("PavBhaji POS") == true)
                            }
                        } catch (e: Exception) { false }
                    }
                    busy = false
                    if (ok) {
                        Deps.session.serverAddr = target
                        onConnected()
                    } else {
                        error = "Couldn't find the server there. Same WiFi? Address correct?"
                    }
                }
            },
            enabled = !busy && addr.length >= 7,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
        ) {
            if (busy) CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
            else Text("Connect", fontSize = 16.sp)
        }
    }
}
