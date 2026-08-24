package com.pavbhaji.pos.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.pavbhaji.pos.data.AppUpdater
import kotlinx.coroutines.delay

// The whole update experience on a phone: nothing.
//
// This just keeps a quiet loop running while somebody is logged in — ask the
// server, pull anything newer down in the background. The swap itself happens
// when the phone is put down (MainActivity.onStop), so no order is ever
// interrupted and no waiter is ever asked to decide anything.
//
// One dialog survives, and only once per phone: Android's own switch that lets
// an app install anything at all. Without it the update can be downloaded but
// never applied, so it is worth interrupting for.
@Composable
fun UpdateGate() {
    val ctx = LocalContext.current

    LaunchedEffect(Unit) {
        while (true) {
            AppUpdater.poll(ctx)
            delay(5 * 60 * 1000L)
        }
    }

    if (AppUpdater.needsInstallPermission) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(S.updateAllowTitle) },
            text = { Text(S.updateAllowBody) },
            confirmButton = {
                TextButton(onClick = { AppUpdater.openInstallPermission(ctx) }) {
                    Text(S.updateOpenSettings)
                }
            },
            dismissButton = {
                TextButton(onClick = { AppUpdater.dismissPermissionPrompt() }) {
                    Text(S.updateLater)
                }
            }
        )
    }
}
