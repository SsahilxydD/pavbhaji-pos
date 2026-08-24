package com.pavbhaji.pos.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

// How Android answers a commit: done, refused, or "ask the human first".
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                AppUpdater.onInstallNeedsUser(confirm)
            }
            PackageInstaller.STATUS_SUCCESS ->
                AppUpdater.onInstallFinished(ctx.applicationContext, true, null)
            Int.MIN_VALUE -> Unit    // not one of ours
            else -> AppUpdater.onInstallFinished(
                ctx.applicationContext, false,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            )
        }
    }
}
