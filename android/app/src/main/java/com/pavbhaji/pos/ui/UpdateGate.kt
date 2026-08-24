package com.pavbhaji.pos.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pavbhaji.pos.data.AppUpdater
import kotlinx.coroutines.launch

// The whole update experience on a phone: one dialog, once, after the staff
// member is already logged in. It never interrupts an order — it appears on the
// home screen of whatever role they are, and "Later" is always honoured unless
// the build was published as mandatory.
@Composable
fun UpdateGate() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { AppUpdater.check(ctx) }

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
                TextButton(onClick = { AppUpdater.dismiss() }) { Text(S.updateLater) }
            }
        )
        return
    }

    val u = AppUpdater.pending ?: return

    AlertDialog(
        onDismissRequest = { if (!AppUpdater.downloading && !u.mandatory) AppUpdater.dismiss() },
        title = { Text(S.updateTitle + "  " + u.versionName) },
        text = {
            Column {
                if (u.notes.isNotBlank()) Text(u.notes)
                AppUpdater.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it)
                }
                if (AppUpdater.downloading) {
                    Spacer(Modifier.height(12.dp))
                    Text(S.updateDownloading)
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = { AppUpdater.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !AppUpdater.downloading,
                onClick = { scope.launch { AppUpdater.downloadAndInstall(ctx) } }
            ) { Text(S.updateNow) }
        },
        dismissButton = {
            if (!u.mandatory && !AppUpdater.downloading) {
                TextButton(onClick = { AppUpdater.dismiss() }) { Text(S.updateLater) }
            }
        }
    )
}
