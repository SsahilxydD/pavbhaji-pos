package com.pavbhaji.pos.ui.admin

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pavbhaji.pos.Deps
import com.pavbhaji.pos.data.ApiResult
import com.pavbhaji.pos.data.ReportDto
import com.pavbhaji.pos.ui.ConnBanner
import com.pavbhaji.pos.ui.GoodGreen
import com.pavbhaji.pos.ui.StatCard
import com.pavbhaji.pos.ui.chef.KitchenScreen
import com.pavbhaji.pos.ui.rupees
import com.pavbhaji.pos.ui.waiter.WaiterHome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private enum class AdminScreen { Home, Floor, Kitchen, Menu, Staff, Tables, Report, Audit }

@Composable
fun AdminHome(onLogout: () -> Unit) {
    var screen by remember { mutableStateOf(AdminScreen.Home) }
    val back = { screen = AdminScreen.Home }
    when (screen) {
        AdminScreen.Floor -> WaiterHome(onLogout = {}, onBack = back)
        AdminScreen.Kitchen -> KitchenScreen(onLogout = {}, embedded = true, onBack = back)
        AdminScreen.Menu -> MenuAdmin(onBack = back)
        AdminScreen.Staff -> StaffAdmin(onBack = back)
        AdminScreen.Tables -> TablesAdmin(onBack = back)
        AdminScreen.Report -> ReportScreen(onBack = back)
        AdminScreen.Audit -> AuditScreen(onBack = back)
        AdminScreen.Home -> Dashboard(
            onLogout = onLogout,
            onOpen = { screen = it }
        )
    }
}

@Composable
private fun Dashboard(onLogout: () -> Unit, onOpen: (AdminScreen) -> Unit) {
    val connected by Deps.live.connected.collectAsState()
    var rep by remember { mutableStateOf(ReportDto()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busyBackup by remember { mutableStateOf(false) }

    suspend fun refresh() {
        when (val r = Deps.api.dashboard()) {
            is ApiResult.Ok -> rep = r.value
            is ApiResult.Err -> {}
        }
    }
    LaunchedEffect(Unit) {
        refresh()
        Deps.live.topics.collect { refresh() }
    }
    LaunchedEffect(Unit) { while (true) { delay(20_000); refresh() } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Admin", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Text(Deps.session.staffName, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onLogout) { Text("Logout") }
        }
        ConnBanner(connected)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            Text("Today (${rep.date})", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("sales (served)", rupees(rep.soldTotal), Modifier.weight(1f), GoodGreen)
                StatCard("orders", "${rep.ordersOpened}", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("open now", "${rep.ordersOpen}", Modifier.weight(1f))
                StatCard("kitchen queue", "${rep.kitchenPending}", Modifier.weight(1f))
                StatCard("cancelled", rupees(rep.cancelledTotal), Modifier.weight(1f), MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("devices online", "${rep.devicesOnline}", Modifier.weight(1f))
                StatCard("items sold", "${rep.soldQty}", Modifier.weight(1f))
            }

            Spacer(Modifier.height(18.dp))
            val navItems = listOf(
                Triple("🍽  Floor — take & serve orders", AdminScreen.Floor, true),
                Triple("👨‍🍳  Kitchen view", AdminScreen.Kitchen, true),
                Triple("📋  Menu & prices", AdminScreen.Menu, true),
                Triple("👥  Staff & PINs", AdminScreen.Staff, true),
                Triple("🪑  Tables setup", AdminScreen.Tables, true),
                Triple("📊  Day report", AdminScreen.Report, true),
                Triple("🧾  Audit log", AdminScreen.Audit, true),
            )
            navItems.forEach { (label, target, _) ->
                Surface(
                    onClick = { onOpen(target) },
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    shadowElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(label, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(14.dp))
                }
            }
            Surface(
                onClick = {
                    if (!busyBackup) {
                        busyBackup = true
                        scope.launch {
                            when (val r = Deps.api.backupBytes()) {
                                is ApiResult.Ok -> {
                                    val name = "pbpos-backup-" + System.currentTimeMillis() + ".db"
                                    val where = saveToDownloads(context, name, r.value)
                                    Toast.makeText(context, "Backup saved: $where", Toast.LENGTH_LONG).show()
                                }
                                is ApiResult.Err ->
                                    Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
                            }
                            busyBackup = false
                        }
                    }
                },
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
            ) {
                Text(
                    if (busyBackup) "💾  Downloading backup…" else "💾  Download backup to this phone",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(14.dp)
                )
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

private fun saveToDownloads(context: Context, name: String, bytes: ByteArray): String {
    return try {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let { context.contentResolver.openOutputStream(it)?.use { os -> os.write(bytes) } }
            "Downloads/$name"
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val f = File(dir, name)
            f.writeBytes(bytes)
            f.absolutePath
        }
    } catch (e: Exception) {
        "failed: ${e.message}"
    }
}
