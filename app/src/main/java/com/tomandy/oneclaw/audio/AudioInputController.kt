package com.tomandy.oneclaw.audio

import com.tomandy.oneclaw.data.ModelPreferences
import com.tomandy.oneclaw.llm.LlmProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class AudioState { IDLE, LISTENING, RECORDING }

class AudioInputController(
    private val audioRecorder: AudioRecorder,
    private val sttProvider: AndroidSttProvider,
    private val modelPreferences: ModelPreferences
) {
    private val _state = MutableStateFlow(AudioState.IDLE)
    val state: StateFlow<AudioState> = _state.asStateFlow()

    fun willSendNativeAudio(currentProvider: LlmProvider): Boolean {
        return modelPreferences.getAudioInputMode() == ModelPreferences.AudioInputMode.NATIVE_WHEN_SUPPORTED &&
            currentProvider.supportsAudioInput
    }

    /**
     * Whether the mic button should be available for the current provider + mode.
     * In ALWAYS_TRANSCRIBE mode, mic is always available (STT works with any provider).
     * In NATIVE_WHEN_SUPPORTED mode, mic is only available when the provider supports audio.
     */
    fun isMicAvailable(currentProvider: LlmProvider): Boolean {
        return when (modelPreferences.getAudioInputMode()) {
            ModelPreferences.AudioInputMode.ALWAYS_TRANSCRIBE -> true
            ModelPreferences.AudioInputMode.NATIVE_WHEN_SUPPORTED -> currentProvider.supportsAudioInput
        }
    }

    fun startRecording(conversationId: String): String {
        val path = audioRecorder.start(conversationId)
        _state.value = AudioState.RECORDING
        return path
    }

    fun stopRecording(): String? {
        _state.value = AudioState.IDLE
        return audioRecorder.stop()
    }

    fun cancelRecording() {
        audioRecorder.cancel()
        _state.value = AudioState.IDLE
    }

    fun setListening() {
        _state.value = AudioState.LISTENING
    }

    fun setIdle() {
        _state.value = AudioState.IDLE
    }
}
