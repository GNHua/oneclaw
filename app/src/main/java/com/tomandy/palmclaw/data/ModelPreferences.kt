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
}
