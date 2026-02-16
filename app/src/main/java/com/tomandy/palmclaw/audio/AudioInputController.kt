package com.tomandy.palmclaw.audio

import com.tomandy.palmclaw.data.ModelPreferences
import com.tomandy.palmclaw.llm.LlmProvider
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

    fun startRecording(conversationId: String): String {
        val path = audioRecorder.start(conversationId)
        _state.value = AudioState.RECORDING
        return path
    }

    fun stopRecording(): String? {
        val path = audioRecorder.stop()
        _state.value = AudioState.IDLE
        return path
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
