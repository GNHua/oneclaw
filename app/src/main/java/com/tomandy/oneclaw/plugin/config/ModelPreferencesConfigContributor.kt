package com.tomandy.oneclaw.plugin.config

import com.tomandy.oneclaw.data.ModelPreferences
import com.tomandy.oneclaw.data.ModelPreferences.AudioInputMode
import com.tomandy.oneclaw.plugin.ConfigContributor
import com.tomandy.oneclaw.plugin.ConfigEntry
import com.tomandy.oneclaw.plugin.ConfigType

class ModelPreferencesConfigContributor(
    private val modelPreferences: ModelPreferences
) : ConfigContributor {

    override fun contribute(): List<ConfigEntry> = listOf(
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
