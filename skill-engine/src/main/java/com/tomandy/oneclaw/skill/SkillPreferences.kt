package com.tomandy.oneclaw.skill

import android.content.Context
import android.content.SharedPreferences

class SkillPreferences(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences(
        "skill_preferences", Context.MODE_PRIVATE
    )

    fun isSkillEnabled(skillName: String, default: Boolean = true): Boolean {
        return prefs.getBoolean("enabled_$skillName", default)
    }

    fun setSkillEnabled(skillName: String, enabled: Boolean) {
        prefs.edit().putBoolean("enabled_$skillName", enabled).apply()
    }
}
