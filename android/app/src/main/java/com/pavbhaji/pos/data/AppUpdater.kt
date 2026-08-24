package com.pavbhaji.pos.data

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.pavbhaji.pos.Deps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// Updating phones in a shop nobody visits, with no computer in it.
//
// The server is on the VPS, so a phone already has the internet in its hand:
// it asks the server what the newest build is, pulls it down in the background
// while the staff carry on taking orders, and swaps it in at a moment that
// costs nobody an order — when the phone is put down.
//
// Android will do that swap without a single tap IF the app is the one that
// installed the running copy (API 31+). The very first update after a manual
// sideload is still Android's own confirm screen; from then on it is silent.
object AppUpdater {

    // Only ever surfaces one-time Android obstacles — never "an update exists".
    var needsInstallPermission by mutableStateOf(false)
        private set
    var userAction by mutableStateOf<Intent?>(null)   // Android wants a tap after all
        private set
    var lastError by mutableStateOf<String?>(null)
        private set

    private const val TAG = "pbpos-update"
    private const val CHECK_EVERY_MS = 15 * 60 * 1000L

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private var lastCheck = 0L
    private var installing = false

    /** The versionCode of the APK currently running. */
    fun currentVersionCode(ctx: Context): Long = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (_: Exception) {
        0L
    }

    // ---- fetching -------------------------------------------------------

    /**
     * Ask the server what it has and, if it is newer, pull it down. Silent
     * throughout: a phone that cannot reach the server has louder problems
     * than a missed update check, and a new build is not news to a waiter.
     */
    suspend fun poll(ctx: Context, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && lastCheck != 0L && now - lastCheck < CHECK_EVERY_MS) return
        if (gate.isLocked) return
        gate.withLock {
            lastCheck = SystemClock.elapsedRealtime()
            val u = when (val r = Deps.api.appUpdate()) {
                is ApiResult.Ok -> r.value
                is ApiResult.Err -> return
            }
            if (!u.available || u.versionCode <= currentVersionCode(ctx)) return
            if (staged(ctx, u.versionCode) != null) return   // already on disk
            try {
                withContext(Dispatchers.IO) { fetch(ctx, u) }
                Log.i(TAG, "staged ${u.versionName} (${u.versionCode})")
            } catch (e: Exception) {
                lastError = e.message
                Log.w(TAG, "download failed: ${e.message}")
            }
        }
    }

    /** A verified APK for [code] sitting in the cache, or null. */
    private fun staged(ctx: Context, code: Int): File? =
        File(updateDir(ctx), "pbpos-$code.apk").takeIf { it.isFile && it.length() > 0 }

    /** The newest verified APK on disk that is newer than what is running. */
    fun stagedNewer(ctx: Context): File? {
        val mine = currentVersionCode(ctx)
        return updateDir(ctx).listFiles()
            ?.filter { it.name.startsWith("pbpos-") && it.name.endsWith(".apk") }
            ?.mapNotNull { f ->
                f.name.removePrefix("pbpos-").removeSuffix(".apk").toLongOrNull()?.let { it to f }
            }
            ?.filter { it.first > mine }
            ?.maxByOrNull { it.first }
            ?.second
    }

    private fun updateDir(ctx: Context) = File(ctx.cacheDir, "updates").apply { mkdirs() }

    // Its own client: an 11 MB APK outlives the 10s read timeout the order
    // screens are tuned for.
    private val downloadHttp: OkHttpClient by lazy {
        Deps.api.http.newBuilder()
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    /** Download, checksum, and only then give the file its real name. */
    private fun fetch(ctx: Context, u: AppUpdateDto): File {
        val dir = updateDir(ctx)
        dir.listFiles()?.forEach { it.delete() }        // only ever keep the one we want
        val part = File(dir, "pbpos-${u.versionCode}.part")
        val dest = File(dir, "pbpos-${u.versionCode}.apk")

        val req = Request.Builder()
            .url(Deps.api.baseUrl() + u.url)
            .header("Authorization", "Bearer " + Deps.session.token)
            .build()

        downloadHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("server said ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("empty download")
            body.byteStream().use { input ->
                part.outputStream().use { out -> input.copyTo(out, 64 * 1024) }
            }
        }

        if (u.sha256.isNotEmpty() && sha256Of(part) != u.sha256) {
            part.delete()
            throw IllegalStateException("download was corrupted")
        }
        part.renameTo(dest)
        return dest
    }

    private fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- swapping in ----------------------------------------------------

    /**
     * Install whatever is staged. Called when the phone is put down and again
     * at the next start — never in the middle of a shift, because a swap kills
     * this process. Does nothing if there is nothing to install.
     */
    fun applyStaged(ctx: Context) {
        if (installing) return
        val apk = stagedNewer(ctx) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !ctx.packageManager.canRequestPackageInstalls()
        ) {
            needsInstallPermission = true
            return
        }
        needsInstallPermission = false
        installing = true
        val app = ctx.applicationContext
        io.launch {
            try {
                commitSession(app, apk)
            } catch (e: Exception) {
                Log.w(TAG, "PackageInstaller refused: ${e.message}")
                lastError = e.message
                installing = false
                handOffToSystemInstaller(app, apk)
            }
        }
    }

    /**
     * Stream the APK into a PackageInstaller session and commit it. On API 31+
     * we ask for no user action at all; Android grants that once this app is
     * the installer of record for itself, and otherwise answers with an intent
     * we show the next time somebody is looking at the screen.
     */
    private fun commitSession(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL
        )
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("pbpos", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 64 * 1024) }
                session.fsync(out)
            }
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                flags = flags or PendingIntent.FLAG_MUTABLE
            }
            val callback = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallReceiver::class.java), flags
            )
            session.commit(callback.intentSender)
        }
        Log.i(TAG, "install session $id committed for ${apk.name}")
    }

    /** Last resort on phones whose OEM blocks silent self-installs. */
    private fun handOffToSystemInstaller(ctx: Context, apk: File) {
        try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", apk)
            userAction = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } catch (e: Exception) {
            lastError = e.message
        }
    }

    // ---- called back by InstallReceiver ---------------------------------

    internal fun onInstallNeedsUser(intent: Intent?) {
        installing = false
        userAction = intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    internal fun onInstallFinished(ctx: Context, ok: Boolean, message: String?) {
        installing = false
        userAction = null
        if (ok) {
            updateDir(ctx).listFiles()?.forEach { it.delete() }
        } else {
            lastError = message
            Log.w(TAG, "install failed: $message")
        }
    }

    /** The pending confirm screen, shown only while the app is in front. */
    fun consumeUserAction(ctx: Context) {
        val i = userAction ?: return
        userAction = null
        try {
            ctx.startActivity(i)
        } catch (e: Exception) {
            lastError = e.message
        }
    }

    // ---- the one-time Android toggle ------------------------------------

    fun dismissPermissionPrompt() {
        needsInstallPermission = false
    }

    /** Send the owner to the one Android settings screen that unblocks installs. */
    fun openInstallPermission(ctx: Context) {
        needsInstallPermission = false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val i = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:" + ctx.packageName)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (_: Exception) {
            lastError = "open Settings → Apps → PavBhaji POS → Install unknown apps"
        }
    }
}
