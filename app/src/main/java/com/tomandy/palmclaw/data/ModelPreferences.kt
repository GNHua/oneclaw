package com.tomandy.palmclaw.data

import android.content.Context
import android.content.SharedPreferences
import com.tomandy.palmclaw.llm.LlmProvider

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

    fun getMaxIterations(): Int {
        return prefs.getInt("max_iterations", DEFAULT_MAX_ITERATIONS)
    }

    fun saveMaxIterations(value: Int) {
        prefs.edit()
            .putInt("max_iterations", value.coerceIn(1, 500))
            .apply()
    }

    fun getTemperature(): Float {
        return prefs.getFloat("temperature", DEFAULT_TEMPERATURE)
    }

    fun saveTemperature(value: Float) {
        prefs.edit()
            .putFloat("temperature", value.coerceIn(0f, 2f))
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

    enum class AudioInputMode { ALWAYS_TRANSCRIBE, NATIVE_WHEN_SUPPORTED }

    fun getAudioInputMode(): AudioInputMode {
        val value = prefs.getString("audio_input_mode", AudioInputMode.ALWAYS_TRANSCRIBE.name)
        return try {
            AudioInputMode.valueOf(value ?: AudioInputMode.ALWAYS_TRANSCRIBE.name)
        } catch (_: Exception) {
            AudioInputMode.ALWAYS_TRANSCRIBE
        }
    }

    fun saveAudioInputMode(mode: AudioInputMode) {
        prefs.edit()
            .putString("audio_input_mode", mode.name)
            .apply()
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
        const val DEFAULT_MAX_ITERATIONS = 200
        const val DEFAULT_TEMPERATURE = 0.7f
        const val DEFAULT_SYSTEM_PROMPT = "You are a helpful AI assistant. Be concise and accurate."
    }
}
