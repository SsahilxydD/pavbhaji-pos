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
import com.pavbhaji.pos.data.ApiResult
import com.pavbhaji.pos.data.CategoryDto
import com.pavbhaji.pos.data.MenuItemDto
import com.pavbhaji.pos.ui.rupees
import kotlinx.coroutines.launch

// Rupee text like "120" or "120.50" → paise, or null if not a valid amount.
fun parseRupees(s: String): Long? {
    val t = s.trim().removePrefix("₹").trim()
    if (t.isEmpty()) return null
    return try {
        val parts = t.split(".")
        val whole = parts[0].ifEmpty { "0" }.toLong()
        val frac = if (parts.size > 1) (parts[1] + "00").take(2).toLong() else 0
        if (whole < 0 || parts.size > 2) null else whole * 100 + frac
    } catch (e: Exception) { null }
}

@Composable
fun MenuAdmin(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf<List<CategoryDto>>(emptyList()) }
    var editItem by remember { mutableStateOf<MenuItemDto?>(null) }
    var addToCat by remember { mutableStateOf<CategoryDto?>(null) }
    var addCategory by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        when (val m = Deps.api.menu()) {
            is ApiResult.Ok -> { menu = m.value.menu; err = null }
            is ApiResult.Err -> err = m.message
        }
    }
    LaunchedEffect(Unit) {
        refresh()
        Deps.live.topics.collect { if (it == "menu") refresh() }
    }

    Column(Modifier.fillMaxSize()) {
        com.pavbhaji.pos.ui.AppHeader(
            title = "Menu & prices",
            onBack = onBack,
            actions = { com.pavbhaji.pos.ui.HeaderTextAction("+ category") { addCategory = true } }
        )
        err?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 14.dp)) }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
            menu.forEach { cat ->
                item(key = "cat${cat.id}") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            cat.name.uppercase(), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { addToCat = cat }) { Text("+ item", fontSize = 13.sp) }
                    }
                }
                cat.items.forEach { item ->
                    item(key = "mi${item.id}") {
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(rupees(item.price), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { editItem = item }) { Text("edit", fontSize = 13.sp) }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Switch(
                                    checked = item.available,
                                    onCheckedChange = { on ->
                                        scope.launch {
                                            Deps.api.menuItemPatch(item.id, """{"available":$on}""")
                                            refresh()
                                        }
                                    }
                                )
                                Text(
                                    if (item.available) "in stock" else "OUT",
                                    fontSize = 10.sp,
                                    color = if (item.available) MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.padding(30.dp)) }
        }
    }

    // ---- dialogs ----
    editItem?.let { it ->
        var name by remember { mutableStateOf(it.name) }
        var price by remember { mutableStateOf(if (it.price % 100 == 0L) "${it.price / 100}" else "${it.price / 100.0}") }
        AlertDialog(
            onDismissRequest = { editItem = null },
            title = { Text("Edit item") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                    OutlinedTextField(
                        value = price, onValueChange = { price = it },
                        label = { Text("Price (₹)") }, modifier = Modifier.padding(top = 8.dp)
                    )
                    TextButton(onClick = {
                        scope.launch {
                            Deps.api.menuItemPatch(it.id, """{"active":false}""")
                            editItem = null; refresh()
                        }
                    }) { Text("Archive item (hide everywhere)", color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val p = parseRupees(price)
                    if (p != null && name.trim().isNotEmpty()) {
                        scope.launch {
                            Deps.api.menuItemPatch(
                                it.id,
                                """{"name":${Deps.api.json.encodeToString(kotlinx.serialization.serializer<String>(), name.trim())},"price":$p}"""
                            )
                            editItem = null; refresh()
                        }
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editItem = null }) { Text("Back") } }
        )
    }
    addToCat?.let { cat ->
        var name by remember { mutableStateOf("") }
        var price by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addToCat = null },
            title = { Text("New item in ${cat.name}") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                    OutlinedTextField(
                        value = price, onValueChange = { price = it },
                        label = { Text("Price (₹)") }, modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val p = parseRupees(price)
                    if (p != null && name.trim().isNotEmpty()) {
                        scope.launch {
                            Deps.api.menuItemCreate(cat.id, name.trim(), p)
                            addToCat = null; refresh()
                        }
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addToCat = null }) { Text("Back") } }
        )
    }
    if (addCategory) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addCategory = false },
            title = { Text("New category") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }) },
            confirmButton = {
                Button(onClick = {
                    if (name.trim().isNotEmpty()) {
                        scope.launch {
                            Deps.api.categoryCreate(name.trim())
                            addCategory = false; refresh()
                        }
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { addCategory = false }) { Text("Back") } }
        )
    }
}
