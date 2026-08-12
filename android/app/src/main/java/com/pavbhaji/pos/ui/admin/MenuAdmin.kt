package com.pavbhaji.pos.ui.admin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pavbhaji.pos.Deps
import com.pavbhaji.pos.data.ApiResult
import com.pavbhaji.pos.data.CategoryDto
import com.pavbhaji.pos.data.MenuItemDto
import com.pavbhaji.pos.ui.AppHeader
import com.pavbhaji.pos.ui.CardLine
import com.pavbhaji.pos.ui.DishPaletteHex
import com.pavbhaji.pos.ui.HeaderTextAction
import com.pavbhaji.pos.ui.S
import com.pavbhaji.pos.ui.InkDark
import com.pavbhaji.pos.ui.WarnAmber
import com.pavbhaji.pos.ui.dishColour
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

// A dish's colour on every screen; blank falls back the same way dishColour does.
private fun colourOf(mi: MenuItemDto): String =
    mi.colour.ifBlank { DishPaletteHex[(mi.id % DishPaletteHex.size).toInt()] }

// One card per dish: tap the swatch to recolour, tap the card to edit,
// flip the switch when it runs out. That's the whole screen.
@Composable
fun MenuAdmin(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf<List<CategoryDto>>(emptyList()) }
    var editItem by remember { mutableStateOf<MenuItemDto?>(null) }
    var colourFor by remember { mutableStateOf<MenuItemDto?>(null) }
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
        AppHeader(
            title = S.navMenu,
            onBack = onBack,
            actions = { HeaderTextAction(S.addCategory) { addCategory = true } }
        )
        com.pavbhaji.pos.ui.ErrorBanner(err)

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            menu.forEach { cat ->
                item(key = "cat${cat.id}") {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            cat.name.uppercase(),
                            fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { addToCat = cat }) { Text(S.addItem, fontSize = 13.sp) }
                    }
                }
                cat.items.forEach { mi ->
                    item(key = "mi${mi.id}") {
                        MenuItemCard(
                            mi,
                            onEdit = { editItem = mi },
                            onColour = { colourFor = mi },
                            onToggle = { on ->
                                scope.launch {
                                    Deps.api.menuItemPatch(mi.id, """{"available":$on}""")
                                    refresh()
                                }
                            }
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(30.dp)) }
        }
    }

    // ---- colour ----
    colourFor?.let { mi ->
        ColourPickerDialog(
            title = mi.name,
            initial = colourOf(mi),
            onPick = { hex ->
                scope.launch {
                    Deps.api.menuItemPatch(mi.id, """{"colour":"$hex"}""")
                    colourFor = null
                    refresh()
                }
            },
            onDismiss = { colourFor = null }
        )
    }

    // ---- edit ----
    editItem?.let { mi ->
        var name by remember(mi.id) { mutableStateOf(mi.name) }
        var price by remember(mi.id) {
            mutableStateOf(if (mi.price % 100 == 0L) "${mi.price / 100}" else "${mi.price / 100.0}")
        }
        var station by remember(mi.id) { mutableStateOf(mi.station) }
        var cap by remember(mi.id) { mutableStateOf(mi.batchCap?.toString() ?: "") }
        var ageMin by remember(mi.id) { mutableStateOf(mi.batchAgeMin?.toString() ?: "") }
        AlertDialog(
            onDismissRequest = { editItem = null },
            title = { Text(mi.name) },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(S.name) })
                    OutlinedTextField(
                        value = price, onValueChange = { price = it },
                        label = { Text(S.priceRs) }, modifier = Modifier.padding(top = 8.dp)
                    )
                    StationPicker(station) { station = it }
                    if (station == "kitchen") {
                        // Bucket tuning: blank = shop default. Cap = plates per tawa
                        // load; the timer seals a waiting bucket so quiet-hour
                        // orders don't rot while the bucket hunts for fullness.
                        Row(Modifier.padding(top = 8.dp)) {
                            OutlinedTextField(
                                value = cap,
                                onValueChange = { cap = it.filter { c -> c.isDigit() }.take(2) },
                                label = { Text(S.tawaCap) }, placeholder = { Text("15") },
                                modifier = Modifier.weight(1f).padding(end = 6.dp)
                            )
                            OutlinedTextField(
                                value = ageMin,
                                onValueChange = { ageMin = it.filter { c -> c.isDigit() }.take(3) },
                                label = { Text(S.sealAfter) }, placeholder = { Text("10") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    TextButton(onClick = {
                        scope.launch {
                            Deps.api.menuItemPatch(mi.id, """{"active":false}""")
                            editItem = null; refresh()
                        }
                    }) { Text(S.archiveItem, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val p = parseRupees(price)
                    if (p != null && name.trim().isNotEmpty()) {
                        scope.launch {
                            val tuning = if (station == "kitchen")
                                ""","batchCap":${cap.toLongOrNull() ?: 0},"batchAgeMin":${ageMin.toLongOrNull() ?: 0}"""
                            else ""
                            Deps.api.menuItemPatch(
                                mi.id,
                                """{"name":${Deps.api.json.encodeToString(kotlinx.serialization.serializer<String>(), name.trim())},"price":$p,"station":"$station"$tuning}"""
                            )
                            editItem = null; refresh()
                        }
                    }
                }) { Text(S.save) }
            },
            dismissButton = { TextButton(onClick = { editItem = null }) { Text(S.back) } }
        )
    }

    // ---- add item ----
    addToCat?.let { cat ->
        var name by remember(cat.id) { mutableStateOf("") }
        var price by remember(cat.id) { mutableStateOf("") }
        var colour by remember(cat.id) { mutableStateOf(DishPaletteHex[cat.items.size % DishPaletteHex.size]) }
        var station by remember(cat.id) { mutableStateOf("kitchen") }
        var morePicker by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { addToCat = null },
            title = { Text(S.newItemIn(cat.name)) },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(S.name) })
                    OutlinedTextField(
                        value = price, onValueChange = { price = it },
                        label = { Text(S.priceRs) }, modifier = Modifier.padding(top = 8.dp)
                    )
                    StationPicker(station) { station = it }
                    ColourSwatchRow(colour, onSelect = { colour = it }, onMore = { morePicker = true })
                }
            },
            confirmButton = {
                Button(onClick = {
                    val p = parseRupees(price)
                    if (p != null && name.trim().isNotEmpty()) {
                        scope.launch {
                            Deps.api.menuItemCreate(cat.id, name.trim(), p, colour, station)
                            addToCat = null; refresh()
                        }
                    }
                }) { Text(S.add) }
            },
            dismissButton = { TextButton(onClick = { addToCat = null }) { Text(S.back) } }
        )
        if (morePicker) {
            ColourPickerDialog(
                title = name.trim().ifBlank { S.newItem },
                initial = colour,
                onPick = { colour = it; morePicker = false },
                onDismiss = { morePicker = false }
            )
        }
    }

    // ---- add category ----
    if (addCategory) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addCategory = false },
            title = { Text(S.newCategory) },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(S.name) }) },
            confirmButton = {
                Button(onClick = {
                    if (name.trim().isNotEmpty()) {
                        scope.launch {
                            Deps.api.categoryCreate(name.trim())
                            addCategory = false; refresh()
                        }
                    }
                }) { Text(S.add) }
            },
            dismissButton = { TextButton(onClick = { addCategory = false }) { Text(S.back) } }
        )
    }
}

@Composable
private fun MenuItemCard(
    mi: MenuItemDto,
    onEdit: () -> Unit,
    onColour: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        onClick = onEdit,
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        border = BorderStroke(1.dp, CardLine),
        shadowElevation = 1.dp
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Generous tap target around the swatch — this is the colour editor.
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onColour),
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(24.dp).background(dishColour(mi.colour, mi.id), RoundedCornerShape(8.dp)))
            }
            Column(Modifier.weight(1f).padding(start = 2.dp)) {
                Text(
                    mi.name, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold,
                    color = if (mi.available) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                val sub = buildString {
                    append(rupees(mi.price))
                    if (mi.station == "counter") append(" · " + S.counterTag)
                    if (!mi.available) append(" · " + S.outOfStock)
                }
                Text(
                    sub, fontSize = 12.sp, maxLines = 1,
                    color = when {
                        !mi.available -> MaterialTheme.colorScheme.error
                        mi.station == "counter" -> WarnAmber
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Switch(checked = mi.available, onCheckedChange = onToggle)
        }
    }
}

// Where does this dish live? Kitchen items cook in colour buckets;
// counter items skip the kitchen and land straight on the waiter's Ready line.
@Composable
private fun StationPicker(station: String, onSelect: (String) -> Unit) {
    Row(Modifier.padding(top = 10.dp)) {
        FilterChip(
            selected = station == "kitchen", onClick = { onSelect("kitchen") },
            label = { Text(S.stationKitchen) }, modifier = Modifier.padding(end = 6.dp)
        )
        FilterChip(
            selected = station == "counter", onClick = { onSelect("counter") },
            label = { Text(S.stationCounter) }
        )
    }
}

// Quick palette for the add dialog; the last slot opens the full picker
// and shows the custom colour once one is mixed.
@Composable
private fun ColourSwatchRow(selected: String, onSelect: (String) -> Unit, onMore: () -> Unit) {
    Column(Modifier.padding(top = 12.dp)) {
        Text(S.bucketColour, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DishPaletteHex.forEach { hex ->
                val sel = selected.equals(hex, ignoreCase = true)
                Box(
                    Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(dishColour(hex, 0))
                        .border(
                            if (sel) 3.dp else 1.dp,
                            if (sel) InkDark else Color(0x22000000),
                            RoundedCornerShape(9.dp)
                        )
                        .clickable { onSelect(hex) }
                )
            }
            val custom = DishPaletteHex.none { it.equals(selected, ignoreCase = true) }
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (custom) dishColour(selected, 0) else Color.Transparent)
                    .border(
                        if (custom) 3.dp else 1.dp,
                        if (custom) InkDark else Color(0x44000000),
                        RoundedCornerShape(9.dp)
                    )
                    .clickable(onClick = onMore),
                contentAlignment = Alignment.Center
            ) {
                if (!custom) Text("＋", fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
