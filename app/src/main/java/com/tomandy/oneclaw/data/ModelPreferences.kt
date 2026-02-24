package com.tomandy.oneclaw.data

import android.content.Context
import android.content.SharedPreferences
import com.tomandy.oneclaw.llm.LlmProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages model selection preferences
 */
class ModelPreferences(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "model_preferences",
        Context.MODE_PRIVATE
    )

    /**
     * Save the currently selected model (global, not per-provider)
     */
    fun saveSelectedModel(model: String) {
        prefs.edit()
            .putString("selected_model", model)
            .apply()
    }

    /**
     * Get the currently selected model
     */
    fun getSelectedModel(): String? {
        return prefs.getString("selected_model", null)
    }

    /**
     * Get the selected model for a provider, or return the default
     */
    fun getModel(provider: LlmProvider): String {
        return prefs.getString("model_${provider.name}", provider.defaultModel)
            ?: provider.defaultModel
    }

    /**
     * Save the selected model for a provider (backward compatibility)
     */
    fun saveModel(provider: LlmProvider, model: String) {
        prefs.edit()
            .putString("model_${provider.name}", model)
            .apply()
    }

    fun getSystemPrompt(): String {
        return prefs.getString("system_prompt", DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
    }

    fun saveSystemPrompt(value: String) {
        prefs.edit()
            .putString("system_prompt", value)
            .apply()
    }

    enum class ThemeMode { SYSTEM, LIGHT, DARK }

    fun getThemeMode(): ThemeMode {
        val value = prefs.getString("theme_mode", ThemeMode.SYSTEM.name)
        return try {
            ThemeMode.valueOf(value ?: ThemeMode.SYSTEM.name)
        } catch (_: Exception) {
            ThemeMode.SYSTEM
        }
    }

    fun saveThemeMode(mode: ThemeMode) {
        prefs.edit()
            .putString("theme_mode", mode.name)
            .apply()
    }

    enum class AudioInputMode { ALWAYS_TRANSCRIBE, NATIVE_WHEN_SUPPORTED }

    private val _audioInputMode = MutableStateFlow(readAudioInputMode())
    val audioInputMode: StateFlow<AudioInputMode> = _audioInputMode.asStateFlow()

    private fun readAudioInputMode(): AudioInputMode {
        val value = prefs.getString("audio_input_mode", AudioInputMode.ALWAYS_TRANSCRIBE.name)
        return try {
            AudioInputMode.valueOf(value ?: AudioInputMode.ALWAYS_TRANSCRIBE.name)
        } catch (_: Exception) {
            AudioInputMode.ALWAYS_TRANSCRIBE
        }
    }

    fun getAudioInputMode(): AudioInputMode = _audioInputMode.value

    fun saveAudioInputMode(mode: AudioInputMode) {
        prefs.edit()
            .putString("audio_input_mode", mode.name)
            .apply()
        _audioInputMode.value = mode
    }

    fun getActiveAgent(): String? {
        return prefs.getString("active_agent", null)
    }

    fun saveActiveAgent(name: String?) {
        prefs.edit()
            .putString("active_agent", name)
            .apply()
    }

    companion object {
        const val DEFAULT_SYSTEM_PROMPT = "You are a helpful AI assistant. Be concise and accurate."
    }
}
