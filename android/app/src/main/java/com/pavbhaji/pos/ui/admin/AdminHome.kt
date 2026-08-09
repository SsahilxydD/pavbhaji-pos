package com.pavbhaji.pos.ui.admin

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
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
import com.pavbhaji.pos.ui.AppHeader
import com.pavbhaji.pos.ui.CardLine
import com.pavbhaji.pos.ui.ConnBanner
import com.pavbhaji.pos.ui.GoodGreen
import com.pavbhaji.pos.ui.S
import com.pavbhaji.pos.ui.HeaderTextAction
import com.pavbhaji.pos.ui.chef.KitchenScreen
import com.pavbhaji.pos.ui.rupees
import com.pavbhaji.pos.ui.waiter.ManagerHome
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private enum class AdminScreen { Home, Floor, Kitchen, Menu, Staff, Tables, Report, Audit }

@Composable
fun AdminHome(onLogout: () -> Unit) {
    var screen by remember { mutableStateOf(AdminScreen.Home) }
    val home = { screen = AdminScreen.Home }
    when (screen) {
        AdminScreen.Floor -> ManagerHome(onLogout = {}, onBack = home)
        AdminScreen.Kitchen -> KitchenScreen(onLogout = {}, embedded = true, onBack = home)
        AdminScreen.Menu -> MenuAdmin(onBack = home)
        AdminScreen.Staff -> StaffAdmin(onBack = home)
        AdminScreen.Tables -> TablesAdmin(onBack = home)
        AdminScreen.Report -> ReportScreen(onBack = home, onOpenLog = { screen = AdminScreen.Audit })
        AdminScreen.Audit -> AuditScreen(onBack = { screen = AdminScreen.Report })
        AdminScreen.Home -> Dashboard(onLogout = onLogout, onOpen = { screen = it })
    }
}

// The whole admin home: one number that matters, six doors, a quiet backup.
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
        AppHeader(
            title = S.adminTitle,
            subtitle = Deps.session.staffName,
            actions = { HeaderTextAction(S.logout, onLogout) }
        )
        ConnBanner(connected)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
            Surface(
                onClick = { onOpen(AdminScreen.Report) },
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, CardLine)
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
                    Text(
                        S.today, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.2.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(rupees(rep.soldTotal), fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, color = GoodGreen)
                    val sub = buildString {
                        append(S.nOrders(rep.ordersOpened))
                        if (rep.ordersOpen > 0) append(" · " + S.nOpen(rep.ordersOpen))
                        if (rep.kitchenPending > 0) append(" · " + S.nCooking(rep.kitchenPending))
                    }
                    Text(sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NavRow(S.navFloor) { onOpen(AdminScreen.Floor) }
                NavRow(S.kitchenTitle) { onOpen(AdminScreen.Kitchen) }
                NavRow(S.navMenu) { onOpen(AdminScreen.Menu) }
                NavRow(S.tablesTitle) { onOpen(AdminScreen.Tables) }
                NavRow(S.navStaff) { onOpen(AdminScreen.Staff) }
                NavRow(S.navReport) { onOpen(AdminScreen.Report) }
            }

            TextButton(
                onClick = {
                    if (!busyBackup) {
                        busyBackup = true
                        scope.launch {
                            when (val r = Deps.api.backupBytes()) {
                                is ApiResult.Ok -> {
                                    val name = "pbpos-backup-" + System.currentTimeMillis() + ".db"
                                    val where = saveToDownloads(context, name, r.value)
                                    Toast.makeText(context, S.backupSaved(where), Toast.LENGTH_LONG).show()
                                }
                                is ApiResult.Err ->
                                    Toast.makeText(context, r.message, Toast.LENGTH_LONG).show()
                            }
                            busyBackup = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) {
                Text(
                    if (busyBackup) S.savingBackup else S.saveBackup,
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun NavRow(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, CardLine),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("›", fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
