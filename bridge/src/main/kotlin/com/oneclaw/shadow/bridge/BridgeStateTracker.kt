package com.oneclaw.shadow.bridge

import com.oneclaw.shadow.bridge.channel.ChannelType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object BridgeStateTracker {

    data class ChannelState(
        val isRunning: Boolean,
        val connectedSince: Long? = null,
        val lastMessageAt: Long? = null,
        val error: String? = null,
        val messageCount: Int = 0
    )

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _channelStates = MutableStateFlow<Map<ChannelType, ChannelState>>(emptyMap())
    val channelStates: StateFlow<Map<ChannelType, ChannelState>> = _channelStates.asStateFlow()

    fun updateServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }

    fun updateChannelState(type: ChannelType, state: ChannelState) {
        _channelStates.update { current ->
            current + (type to state)
        }
    }

    fun removeChannelState(type: ChannelType) {
        _channelStates.update { current ->
            current - type
        }
    }

    private val _newSessionFromBridge = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val newSessionFromBridge: SharedFlow<String> = _newSessionFromBridge.asSharedFlow()

    fun emitNewSessionFromBridge(sessionId: String) {
        _newSessionFromBridge.tryEmit(sessionId)
    }

    private val _activeAppSessionId = MutableStateFlow<String?>(null)
    val activeAppSessionId: StateFlow<String?> = _activeAppSessionId.asStateFlow()

    fun setActiveAppSession(sessionId: String?) {
        _activeAppSessionId.value = sessionId
    }

    fun reset() {
        _serviceRunning.value = false
        _channelStates.value = emptyMap()
    }
}
