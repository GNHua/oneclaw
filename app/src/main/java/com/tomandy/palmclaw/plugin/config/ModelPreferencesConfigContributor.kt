package com.tomandy.palmclaw.plugin.config

import com.tomandy.palmclaw.data.ModelPreferences
import com.tomandy.palmclaw.data.ModelPreferences.AudioInputMode
import com.tomandy.palmclaw.engine.ToolResult
import com.tomandy.palmclaw.plugin.ConfigContributor
import com.tomandy.palmclaw.plugin.ConfigEntry
import com.tomandy.palmclaw.plugin.ConfigType

class ModelPreferencesConfigContributor(
    private val modelPreferences: ModelPreferences
) : ConfigContributor {

    override fun contribute(): List<ConfigEntry> = listOf(
        ConfigEntry(
            key = "max_iterations",
            displayName = "Max Iterations",
            description = "Maximum ReAct loop iterations per message (1-500, default 200).",
            type = ConfigType.IntType(min = 1, max = 500),
            getter = { modelPreferences.getMaxIterations().toString() },
            setter = { modelPreferences.saveMaxIterations(it.toInt()) }
        ),
        ConfigEntry(
            key = "temperature",
            displayName = "Temperature",
            description = "LLM sampling temperature. Lower = more deterministic, higher = more creative (0.0-2.0, default 0.7).",
            type = ConfigType.StringType,
            getter = { modelPreferences.getTemperature().toString() },
            setter = {},
            customHandler = { value ->
                val floatVal = value.toFloatOrNull()
                    ?: return@ConfigEntry ToolResult.Failure(
                        "Invalid value \"$value\" for temperature. Expected a number."
                    )
                if (floatVal !in 0f..2f) {
                    return@ConfigEntry ToolResult.Failure(
                        "temperature must be between 0.0 and 2.0. Got: $floatVal"
                    )
                }
                modelPreferences.saveTemperature(floatVal)
                ToolResult.Success("Temperature changed to $floatVal. Takes effect on the next message.")
            }
        ),
        ConfigEntry(
            key = "system_prompt",
            displayName = "System Prompt",
            description = "Base system prompt sent to the LLM. Defines the assistant's persona and behavior.",
            type = ConfigType.StringType,
            getter = { modelPreferences.getSystemPrompt() },
            setter = { modelPreferences.saveSystemPrompt(it) }
        ),
        ConfigEntry(
            key = "audio_input_mode",
            displayName = "Audio Input Mode",
            description = "How audio input is handled. ALWAYS_TRANSCRIBE converts speech to text before sending. NATIVE_WHEN_SUPPORTED sends raw audio to models that support it.",
            type = ConfigType.EnumType(AudioInputMode.entries.map { it.name }),
            getter = { modelPreferences.getAudioInputMode().name },
            setter = { modelPreferences.saveAudioInputMode(AudioInputMode.valueOf(it)) }
        )
    )
}
