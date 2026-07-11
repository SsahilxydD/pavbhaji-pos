package com.pavbhaji.pos.data

import android.content.Context
import android.content.SharedPreferences

class Session(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("pbpos", Context.MODE_PRIVATE)

    var serverAddr: String
        get() = prefs.getString("serverAddr", "") ?: ""
        set(v) { prefs.edit().putString("serverAddr", v.trim()).apply() }

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) { prefs.edit().putString("token", v).apply() }

    var staffName: String
        get() = prefs.getString("staffName", "") ?: ""
        set(v) { prefs.edit().putString("staffName", v).apply() }

    var staffRole: String
        get() = prefs.getString("staffRole", "") ?: ""
        set(v) { prefs.edit().putString("staffRole", v).apply() }

    fun clearLogin() {
        prefs.edit().remove("token").remove("staffName").remove("staffRole").apply()
    }
}
