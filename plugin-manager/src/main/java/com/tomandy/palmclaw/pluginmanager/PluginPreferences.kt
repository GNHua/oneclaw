package com.tomandy.palmclaw.pluginmanager

import android.content.Context
import android.content.SharedPreferences

class PluginPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "plugin_preferences",
        Context.MODE_PRIVATE
    )

    fun isPluginEnabled(pluginId: String): Boolean {
        return prefs.getBoolean("enabled_$pluginId", true)
    }

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled_$pluginId", enabled).apply()
    }
}
