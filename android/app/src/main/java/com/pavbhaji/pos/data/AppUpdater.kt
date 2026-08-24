package com.pavbhaji.pos.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import com.pavbhaji.pos.Deps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

// Updating a phone in a shop with no computer and nobody to plug a cable in.
//
// The counter PC has already pulled the new APK off the internet; this phone
// only ever talks to that PC, on the same WiFi it uses for orders. So an update
// is: ask the server what it is holding, compare it with what we are running,
// download it over the LAN, check the hash, hand it to Android's installer.
//
// The one thing Android insists on is a human tap — no app may silently replace
// another. So the flow ends at the system install screen, not before it.
object AppUpdater {

    var pending by mutableStateOf<AppUpdateDto?>(null)   // newer than us, ready to fetch
        private set
    var downloading by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0f)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var needsInstallPermission by mutableStateOf(false)
        private set

    private var checkedThisRun = false

    /** The versionCode of the APK currently running. */
    fun currentVersionCode(ctx: Context): Long = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (_: Exception) {
        0L
    }

    fun dismiss() {
        pending = null
        error = null
    }

    /**
     * Ask the shop server what it has. Silent about everything except good news:
     * a phone that cannot reach the server is a problem the order screens will
     * report far more loudly than an update check should.
     */
    suspend fun check(ctx: Context, force: Boolean = false) {
        if (checkedThisRun && !force) return
        checkedThisRun = true
        when (val r = Deps.api.appUpdate()) {
            is ApiResult.Ok -> {
                val u = r.value
                pending = if (u.available && u.versionCode > currentVersionCode(ctx)) u else null
            }
            is ApiResult.Err -> Unit
        }
    }

    /**
     * Pull the APK from the shop server, verify it byte-for-byte, then hand it
     * to Android. A download that fails its checksum is deleted, not installed.
     */
    suspend fun downloadAndInstall(ctx: Context) {
        val u = pending ?: return
        if (downloading) return
        error = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !ctx.packageManager.canRequestPackageInstalls()
        ) {
            needsInstallPermission = true
            return
        }
        needsInstallPermission = false

        downloading = true
        progress = 0f
        try {
            val file = withContext(Dispatchers.IO) { fetch(ctx, u) }
            install(ctx, file)
        } catch (e: Exception) {
            error = e.message ?: "update failed"
        } finally {
            downloading = false
        }
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
            error = "open Settings → Apps → PavBhaji POS → Install unknown apps"
        }
    }

    // Its own client: an 11 MB APK over shop WiFi outlives the 10s read timeout
    // the order screens are tuned for.
    private val downloadHttp: OkHttpClient by lazy {
        Deps.api.http.newBuilder()
            .readTimeout(5, TimeUnit.MINUTES)
            .writeTimeout(5, TimeUnit.MINUTES)
            .build()
    }

    private fun fetch(ctx: Context, u: AppUpdateDto): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }        // only ever keep the one we want
        val dest = File(dir, "pbpos-${u.versionCode}.apk")

        val req = Request.Builder()
            .url(Deps.api.baseUrl() + u.url)
            .header("Authorization", "Bearer " + Deps.session.token)
            .build()

        downloadHttp.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("server said ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("empty download")
            val total = if (u.size > 0) u.size else body.contentLength()
            body.byteStream().use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress = (done.toFloat() / total).coerceIn(0f, 1f)
                    }
                }
            }
        }

        if (u.sha256.isNotEmpty() && sha256Of(dest) != u.sha256) {
            dest.delete()
            throw IllegalStateException("download was corrupted — try again")
        }
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

    private fun install(ctx: Context, apk: File) {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".updates", apk)
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(i)
    }
}
