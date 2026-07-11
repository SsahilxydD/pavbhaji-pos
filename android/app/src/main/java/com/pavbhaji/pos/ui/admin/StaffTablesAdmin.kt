package com.pavbhaji.pos.ui.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pavbhaji.pos.Deps
import com.pavbhaji.pos.data.AdminTableDto
import com.pavbhaji.pos.data.ApiResult
import com.pavbhaji.pos.data.StaffDto
import kotlinx.coroutines.launch

@Composable
fun StaffAdmin(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var staff by remember { mutableStateOf<List<StaffDto>>(emptyList()) }
    var addOpen by remember { mutableStateOf(false) }
    var pinFor by remember { mutableStateOf<StaffDto?>(null) }
    var err by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        when (val r = Deps.api.staffList()) {
            is ApiResult.Ok -> staff = r.value.staff
            is ApiResult.Err -> err = r.message
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize()) {
        com.pavbhaji.pos.ui.AppHeader(
            title = "Staff & PINs",
            onBack = onBack,
            actions = { com.pavbhaji.pos.ui.HeaderTextAction("+ staff") { addOpen = true } }
        )
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 14.dp)) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            staff.forEach { s ->
                item(key = s.id) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                s.role + if (!s.active) " · deactivated" else "",
                                fontSize = 12.sp,
                                color = if (s.active) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error
                            )
                        }
                        TextButton(onClick = { pinFor = s }) { Text("reset PIN", fontSize = 12.sp) }
                        Switch(
                            checked = s.active,
                            onCheckedChange = { on ->
                                scope.launch {
                                    when (val r = Deps.api.staffPatch(s.id, """{"active":$on}""")) {
                                        is ApiResult.Ok -> {}
                                        is ApiResult.Err -> err = r.message
                                    }
                                    refresh()
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (addOpen) {
        var name by remember { mutableStateOf("") }
        var pin by remember { mutableStateOf("") }
        var role by remember { mutableStateOf("waiter") }
        var dErr by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { addOpen = false },
            title = { Text("New staff member") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                    OutlinedTextField(
                        value = pin, onValueChange = { pin = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("PIN (4 digits)") }, modifier = Modifier.padding(top = 8.dp)
                    )
                    Row(Modifier.padding(top = 10.dp)) {
                        listOf("waiter", "chef", "admin").forEach { r ->
                            FilterChip(
                                selected = role == r, onClick = { role = r },
                                label = { Text(r) }, modifier = Modifier.padding(end = 6.dp)
                            )
                        }
                    }
                    dErr?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.trim().isEmpty() || pin.length < 4) { dErr = "name + 4-digit PIN needed"; return@Button }
                    scope.launch {
                        when (val r = Deps.api.staffCreate(name.trim(), pin, role)) {
                            is ApiResult.Ok -> { addOpen = false; refresh() }
                            is ApiResult.Err -> dErr = r.message
                        }
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addOpen = false }) { Text("Back") } }
        )
    }
    pinFor?.let { s ->
        var pin by remember { mutableStateOf("") }
        var dErr by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { pinFor = null },
            title = { Text("New PIN for ${s.name}") },
            text = {
                Column {
                    OutlinedTextField(
                        value = pin, onValueChange = { pin = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("PIN (4 digits)") }
                    )
                    dErr?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (pin.length < 4) { dErr = "at least 4 digits"; return@Button }
                    scope.launch {
                        when (val r = Deps.api.staffPatch(s.id, """{"pin":"$pin"}""")) {
                            is ApiResult.Ok -> pinFor = null
                            is ApiResult.Err -> dErr = r.message
                        }
                    }
                }) { Text("Set PIN") }
            },
            dismissButton = { TextButton(onClick = { pinFor = null }) { Text("Back") } }
        )
    }
}

@Composable
fun TablesAdmin(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var tables by remember { mutableStateOf<List<AdminTableDto>>(emptyList()) }
    var addOpen by remember { mutableStateOf(false) }
    var renameFor by remember { mutableStateOf<AdminTableDto?>(null) }
    var err by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        when (val r = Deps.api.adminTables()) {
            is ApiResult.Ok -> tables = r.value.tables
            is ApiResult.Err -> err = r.message
        }
    }
    LaunchedEffect(Unit) { refresh() }

    Column(Modifier.fillMaxSize()) {
        com.pavbhaji.pos.ui.AppHeader(
            title = "Tables setup",
            onBack = onBack,
            actions = { com.pavbhaji.pos.ui.HeaderTextAction("+ table") { addOpen = true } }
        )
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 14.dp)) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            tables.forEach { t ->
                item(key = t.id) {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            t.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                            color = if (t.active) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { renameFor = t }) { Text("rename", fontSize = 12.sp) }
                        Switch(
                            checked = t.active,
                            onCheckedChange = { on ->
                                scope.launch {
                                    when (val r = Deps.api.tablePatch(t.id, """{"active":$on}""")) {
                                        is ApiResult.Ok -> {}
                                        is ApiResult.Err -> err = r.message
                                    }
                                    refresh()
                                }
                            }
                        )
                    }
                }
            }
            item { Spacer(Modifier.padding(20.dp)) }
        }
    }

    if (addOpen) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addOpen = false },
            title = { Text("New table") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name (e.g. T11)") }) },
            confirmButton = {
                Button(onClick = {
                    if (name.trim().isNotEmpty()) {
                        scope.launch { Deps.api.tableCreate(name.trim()); addOpen = false; refresh() }
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addOpen = false }) { Text("Back") } }
        )
    }
    renameFor?.let { t ->
        var name by remember { mutableStateOf(t.name) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text("Rename ${t.name}") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }) },
            confirmButton = {
                Button(onClick = {
                    if (name.trim().isNotEmpty()) {
                        scope.launch {
                            Deps.api.tablePatch(t.id, """{"name":${Deps.api.json.encodeToString(kotlinx.serialization.serializer<String>(), name.trim())}}""")
                            renameFor = null; refresh()
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("Back") } }
        )
    }
}
