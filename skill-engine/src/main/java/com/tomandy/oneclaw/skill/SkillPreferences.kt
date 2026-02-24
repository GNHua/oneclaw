package com.tomandy.oneclaw.skill

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SkillPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "skill_preferences", Context.MODE_PRIVATE
    )

    private val _changeVersion = MutableStateFlow(0L)
    val changeVersion: StateFlow<Long> = _changeVersion.asStateFlow()

    fun isSkillEnabled(skillName: String, default: Boolean = true): Boolean {
        return prefs.getBoolean("enabled_$skillName", default)
    }

    fun setSkillEnabled(skillName: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled_$skillName", enabled).apply()
        _changeVersion.value++
    }
}
