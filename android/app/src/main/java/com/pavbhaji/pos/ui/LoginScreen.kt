package com.pavbhaji.pos.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onChangeServer: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("🍛 PavBhaji POS", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            S.enterPin,
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
        )
        PinPad(
            onSubmit = { pin ->
                error = null
                scope.launch {
                    when (val r = Deps.api.login(pin)) {
                        is ApiResult.Ok -> {
                            Deps.session.token = r.value.token
                            Deps.session.staffName = r.value.staff.name
                            Deps.session.staffRole = r.value.staff.role
                            Deps.session.staffId = r.value.staff.id
                            onLoggedIn()
                        }
                        is ApiResult.Err -> error = r.message
                    }
                }
            },
            error = error
        )
        LanguageRow()
        TextButton(onClick = onChangeServer, modifier = Modifier.padding(top = 2.dp)) {
            Text("server: ${Deps.session.serverAddr}  ·  ${S.changeServer}", fontSize = 12.sp)
        }
    }
}

// Per-phone language switch. Each label names itself in its own script so a
// waiter who reads only Gujarati can still find their language.
@Composable
private fun LanguageRow() {
    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("en" to "English", "hi" to "हिंदी", "gu" to "ગુજરાતી").forEach { (code, label) ->
            val selected = L10n.lang == code
            TextButton(onClick = {
                L10n.lang = code
                Deps.session.lang = code
            }) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Normal,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
