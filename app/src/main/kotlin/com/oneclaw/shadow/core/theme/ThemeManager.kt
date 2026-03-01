package com.oneclaw.shadow.core.theme

import androidx.appcompat.app.AppCompatDelegate
import com.oneclaw.shadow.core.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromKey(key: String?): ThemeMode = entries.find { it.key == key } ?: SYSTEM
    }
}

class ThemeManager(
    private val settingsRepository: SettingsRepository
) {
    private val _themeMode = MutableStateFlow(ThemeMode.SYSTEM)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    companion object {
        const val SETTINGS_KEY = "theme_mode"
    }

    /**
     * Called once during Application.onCreate() to read the persisted theme
     * and apply it via AppCompatDelegate.
     */
    suspend fun initialize() {
        val stored = settingsRepository.getString(SETTINGS_KEY)
        val mode = ThemeMode.fromKey(stored)
        _themeMode.value = mode
        applyNightMode(mode)
    }

    /**
     * Called from the Settings screen when the user selects a new theme.
     * Persists the choice, updates the StateFlow, and applies via AppCompatDelegate.
     */
    suspend fun setThemeMode(mode: ThemeMode) {
        settingsRepository.setString(SETTINGS_KEY, mode.key)
        _themeMode.value = mode
        applyNightMode(mode)
    }

    private fun applyNightMode(mode: ThemeMode) {
        val nightMode = when (mode) {
            ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
            ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }
}
