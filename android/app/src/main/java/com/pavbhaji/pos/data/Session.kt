package com.pavbhaji.pos.data

import android.content.Context
import android.content.SharedPreferences

class Session(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("pbpos", Context.MODE_PRIVATE)

    companion object {
        // There is no counter PC any more. The POS server lives on the VPS and
        // every phone talks to it over the internet, so a phone needs no setup
        // and no discovery — this address is the shop.
        const val CLOUD_SERVER = "https://200.234.37.253.sslip.io"

        // Bumped whenever a stored serverAddr must be rewritten on upgrade.
        private const val ADDR_EPOCH = 1
    }

    init {
        // Phones upgrading from the LAN era still hold "192.168.x.y:8177", an
        // address that now answers nothing. Move them to the cloud once.
        if (prefs.getInt("addrEpoch", 0) < ADDR_EPOCH) {
            val old = prefs.getString("serverAddr", "") ?: ""
            if (old.isEmpty() || !old.startsWith("http")) {
                prefs.edit().putString("serverAddr", CLOUD_SERVER).apply()
            }
            prefs.edit().putInt("addrEpoch", ADDR_EPOCH).apply()
        }
    }

    // Either a full base URL ("https://host") or the old bare "host:port",
    // which is still accepted so a LAN server can be typed in if one ever
    // comes back.
    var serverAddr: String
        get() = prefs.getString("serverAddr", CLOUD_SERVER) ?: CLOUD_SERVER
        set(v) { prefs.edit().putString("serverAddr", v.trim().trimEnd('/')).apply() }

    /** What every HTTP request is built on, scheme included. */
    fun httpBase(): String = asHttpBase(serverAddr)

    /** The same address as a WebSocket origin — wss for https, ws for http. */
    fun wsBase(): String = httpBase()
        .replaceFirst("https://", "wss://")
        .replaceFirst("http://", "ws://")

    // Identity of "our" server (from /api/ping) so re-discovery after an IP
    // change can tell our server apart from some other PavBhaji server.
    var serverId: String
        get() = prefs.getString("serverId", "") ?: ""
        set(v) { prefs.edit().putString("serverId", v).apply() }

    // UI language for this phone: "en" | "hi" | "gu".
    var lang: String
        get() = prefs.getString("lang", "en") ?: "en"
        set(v) { prefs.edit().putString("lang", v).apply() }

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) { prefs.edit().putString("token", v).apply() }

    var staffName: String
        get() = prefs.getString("staffName", "") ?: ""
        set(v) { prefs.edit().putString("staffName", v).apply() }

    var staffId: Long
        get() = prefs.getLong("staffId", 0)
        set(v) { prefs.edit().putLong("staffId", v).apply() }

    var staffRole: String
        get() = prefs.getString("staffRole", "") ?: ""
        set(v) { prefs.edit().putString("staffRole", v).apply() }

    fun clearLogin() {
        prefs.edit().remove("token").remove("staffName").remove("staffRole").remove("staffId").apply()
    }
}

/** "host:port" → "http://host:port"; anything already carrying a scheme is kept. */
fun asHttpBase(addr: String): String {
    val a = addr.trim().trimEnd('/')
    return if (a.startsWith("http://") || a.startsWith("https://")) a else "http://$a"
}
