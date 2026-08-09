package com.pavbhaji.pos.data

import android.content.Context
import android.content.SharedPreferences

class Session(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("pbpos", Context.MODE_PRIVATE)

    var serverAddr: String
        get() = prefs.getString("serverAddr", "") ?: ""
        set(v) { prefs.edit().putString("serverAddr", v.trim()).apply() }

    // Identity of "our" server (from /api/ping) so re-discovery after an IP
    // change can tell our laptop apart from some other PavBhaji server.
    var serverId: String
        get() = prefs.getString("serverId", "") ?: ""
        set(v) { prefs.edit().putString("serverId", v).apply() }

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
