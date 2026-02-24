package com.tomandy.oneclaw.pluginmanager

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PluginPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "plugin_preferences",
        Context.MODE_PRIVATE
    )

    private val _changeVersion = MutableStateFlow(0L)
    val changeVersion: StateFlow<Long> = _changeVersion.asStateFlow()

    fun isPluginEnabled(pluginId: String): Boolean {
        return prefs.getBoolean("enabled_$pluginId", true)
    }

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled_$pluginId", enabled).apply()
        _changeVersion.value++
    }
}
