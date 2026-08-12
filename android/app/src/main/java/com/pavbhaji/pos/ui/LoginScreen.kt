package com.pavbhaji.pos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
    StatusBarIcons(darkIcons = true)

    Box(Modifier.fillMaxSize().background(Cream)) {
        Column(
            Modifier.fillMaxSize().navigationBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Brand lockup: dish in a warm tile, wordmark under it.
            Box(
                Modifier
                    .size(76.dp)
                    .background(
                        Brush.linearGradient(listOf(Color(0xFFFFE3C2), Color(0xFFFFD1A6))),
                        RoundedCornerShape(24.dp)
                    ),
                contentAlignment = Alignment.Center
            ) { Text("🍛", fontSize = 40.sp) }
            Text(
                "PavBhaji POS",
                fontSize = 25.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp,
                modifier = Modifier.padding(top = 14.dp)
            )
            Text(
                S.enterPin,
                fontSize = 15.sp,
                color = Pb.ink2,
                modifier = Modifier.padding(top = 4.dp, bottom = 22.dp)
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
            Surface(
                onClick = onChangeServer,
                color = Color.Transparent,
                shape = RoundedCornerShape(99.dp),
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text(
                    "${Deps.session.serverAddr}  ·  ${S.changeServer}",
                    fontSize = 12.sp, color = Pb.ink3, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }
        LangAction(
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding()
        )
    }
}
