package com.pavbhaji.pos.ui.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.pavbhaji.pos.ui.S
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
            title = S.staffTitle,
            onBack = onBack,
            actions = { com.pavbhaji.pos.ui.HeaderTextAction(S.addStaff) { addOpen = true } }
        )
        com.pavbhaji.pos.ui.ErrorBanner(err)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            staff.forEach { s ->
                item(key = s.id) {
                    com.pavbhaji.pos.ui.PbCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Initial-letter avatar — instant person recognition in the list.
                            androidx.compose.material3.Surface(
                                color = if (s.active) androidx.compose.ui.graphics.Color(0xFFFFE0CC)
                                else androidx.compose.ui.graphics.Color(0xFFEFE9E2),
                                shape = androidx.compose.foundation.shape.CircleShape
                            ) {
                                androidx.compose.foundation.layout.Box(
                                    Modifier.size(38.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        s.name.trim().take(1).uppercase(),
                                        fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
                                        color = if (s.active) com.pavbhaji.pos.ui.BhajiRedDark
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Column(Modifier.weight(1f).padding(start = 11.dp)) {
                                Text(s.name, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    S.roleLabel(s.role) + if (!s.active) " · " + S.deactivated else "",
                                    fontSize = 12.sp,
                                    color = if (s.active) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                            TextButton(onClick = { pinFor = s }) { Text(S.resetPin, fontSize = 12.sp) }
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
    }

    if (addOpen) {
        var name by remember { mutableStateOf("") }
        var pin by remember { mutableStateOf("") }
        var role by remember { mutableStateOf("waiter") }
        var dErr by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { addOpen = false },
            title = { Text(S.newStaff) },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(S.name) })
                    OutlinedTextField(
                        value = pin, onValueChange = { pin = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text(S.pin4) }, modifier = Modifier.padding(top = 8.dp)
                    )
                    Column(Modifier.padding(top = 4.dp)) {
                        listOf("waiter", "manager", "chef", "cashier", "parcel", "admin").chunked(3).forEach { rowRoles ->
                            Row(Modifier.padding(top = 6.dp)) {
                                rowRoles.forEach { r ->
                                    FilterChip(
                                        selected = role == r, onClick = { role = r },
                                        label = { Text(S.roleLabel(r)) }, modifier = Modifier.padding(end = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                    dErr?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.trim().isEmpty() || pin.length < 4) { dErr = S.namePinNeeded; return@Button }
                    scope.launch {
                        when (val r = Deps.api.staffCreate(name.trim(), pin, role)) {
                            is ApiResult.Ok -> { addOpen = false; refresh() }
                            is ApiResult.Err -> dErr = r.message
                        }
                    }
                }) { Text(S.add) }
            },
            dismissButton = { TextButton(onClick = { addOpen = false }) { Text(S.back) } }
        )
    }
    pinFor?.let { s ->
        var pin by remember { mutableStateOf("") }
        var dErr by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { pinFor = null },
            title = { Text(S.newPinFor(s.name)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = pin, onValueChange = { pin = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text(S.pin4) }
                    )
                    dErr?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (pin.length < 4) { dErr = S.min4digits; return@Button }
                    scope.launch {
                        when (val r = Deps.api.staffPatch(s.id, """{"pin":"$pin"}""")) {
                            is ApiResult.Ok -> pinFor = null
                            is ApiResult.Err -> dErr = r.message
                        }
                    }
                }) { Text(S.setPin) }
            },
            dismissButton = { TextButton(onClick = { pinFor = null }) { Text(S.back) } }
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
            title = S.tablesSetup,
            onBack = onBack,
            actions = { com.pavbhaji.pos.ui.HeaderTextAction(S.addTable) { addOpen = true } }
        )
        com.pavbhaji.pos.ui.ErrorBanner(err)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            tables.forEach { t ->
                item(key = t.id) {
                    com.pavbhaji.pos.ui.PbCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (t.kind == "parcel") "🥡" else "🍽", fontSize = 17.sp)
                            Text(
                                if (t.kind == "parcel") "${t.name} · ${S.parcelTag}" else t.name,
                                fontSize = 15.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f).padding(start = 11.dp),
                                color = if (t.active) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = { renameFor = t }) { Text(S.rename, fontSize = 12.sp) }
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
            }
            item { Spacer(Modifier.padding(20.dp)) }
        }
    }

    if (addOpen) {
        var name by remember { mutableStateOf("") }
        var kind by remember { mutableStateOf("dine") }
        var dErr by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { addOpen = false },
            title = { Text(S.newTable) },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(S.tableNameEg) })
                    // Parcel slots hold walk-up/takeaway orders punched at the
                    // counter — same flow, own section on the tables grid.
                    Row(Modifier.padding(top = 10.dp)) {
                        FilterChip(
                            selected = kind == "dine", onClick = { kind = "dine" },
                            label = { Text(S.dineIn) }, modifier = Modifier.padding(end = 6.dp)
                        )
                        FilterChip(
                            selected = kind == "parcel", onClick = { kind = "parcel" },
                            label = { Text(S.parcelTag) }
                        )
                    }
                    dErr?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.trim().isNotEmpty()) {
                        scope.launch {
                            // Refused (say, a duplicate name): stay open and say why.
                            when (val r = Deps.api.tableCreate(name.trim(), kind)) {
                                is ApiResult.Ok -> { addOpen = false; refresh() }
                                is ApiResult.Err -> dErr = r.message
                            }
                        }
                    }
                }) { Text(S.add) }
            },
            dismissButton = { TextButton(onClick = { addOpen = false }) { Text(S.back) } }
        )
    }
    renameFor?.let { t ->
        var name by remember { mutableStateOf(t.name) }
        var dErr by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { renameFor = null },
            title = { Text(S.renameX(t.name)) },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(S.name) })
                    dErr?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.trim().isNotEmpty()) {
                        scope.launch {
                            when (val r = Deps.api.tablePatch(t.id, """{"name":${Deps.api.json.encodeToString(kotlinx.serialization.serializer<String>(), name.trim())}}""")) {
                                is ApiResult.Ok -> { renameFor = null; refresh() }
                                is ApiResult.Err -> dErr = r.message
                            }
                        }
                    }
                }) { Text(S.save) }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text(S.back) } }
        )
    }
}
