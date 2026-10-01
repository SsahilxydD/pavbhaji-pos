package com.pavbhaji.pos.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.pavbhaji.pos.Deps
import com.pavbhaji.pos.ui.admin.AdminHome
import com.pavbhaji.pos.ui.cashier.CashierHome
import com.pavbhaji.pos.ui.chef.KitchenScreen
import com.pavbhaji.pos.ui.parcel.ParcelHome
import com.pavbhaji.pos.ui.waiter.ManagerHome
import com.pavbhaji.pos.ui.waiter.ServeScreen

sealed class Stage {
    data object Connect : Stage()
    data object Login : Stage()
    data object Home : Stage()
}

@Composable
fun PosApp() {
    val session = Deps.session
    var stage by remember {
        mutableStateOf<Stage>(
            when {
                session.serverAddr.isEmpty() -> Stage.Connect
                session.token.isEmpty() -> Stage.Login
                else -> Stage.Home
            }
        )
    }

    // Server said 401 → session is dead → back to PIN screen.
    DisposableEffect(Unit) {
        Deps.api.onAuthFail = {
            session.clearLogin()
            stage = Stage.Login
        }
        onDispose { Deps.api.onAuthFail = null }
    }

    Surface(Modifier.fillMaxSize()) {
        when (stage) {
            Stage.Connect -> {
                // Opened from the login logo by mistake: Back returns to the
                // PIN pad (there is always the built-in cloud address).
                if (session.serverAddr.isNotEmpty()) {
                    androidx.activity.compose.BackHandler { stage = Stage.Login }
                }
                ConnectScreen(onConnected = { stage = Stage.Login })
            }
            Stage.Login -> LoginScreen(
                onLoggedIn = { stage = Stage.Home },
                onChangeServer = { stage = Stage.Connect }
            )
            Stage.Home -> {
                val logout: () -> Unit = {
                    session.clearLogin()
                    stage = Stage.Login
                }
                // Newer build sitting on the counter PC? Offer it here, once,
                // where nobody is mid-order.
                UpdateGate()
                Column {
                    when (session.staffRole) {
                        "waiter" -> ServeScreen(onLogout = logout)
                        "manager" -> ManagerHome(onLogout = logout)
                        "chef" -> KitchenScreen(onLogout = logout)
                        "admin" -> AdminHome(onLogout = logout)
                        "cashier" -> CashierHome(onLogout = logout)
                        "parcel" -> ParcelHome(onLogout = logout)
                        else -> { session.clearLogin(); stage = Stage.Login }
                    }
                }
            }
        }
    }
}
