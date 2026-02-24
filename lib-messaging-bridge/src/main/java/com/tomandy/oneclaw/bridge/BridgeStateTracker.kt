package com.tomandy.oneclaw.bridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object BridgeStateTracker {

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _channelStates = MutableStateFlow<Map<ChannelType, ChannelState>>(emptyMap())
    val channelStates: StateFlow<Map<ChannelType, ChannelState>> = _channelStates.asStateFlow()

    fun updateServiceRunning(running: Boolean) {
        _serviceRunning.value = running
    }

    fun updateChannelState(type: ChannelType, state: ChannelState) {
        _channelStates.update { current -> current + (type to state) }
    }

    fun removeChannelState(type: ChannelType) {
        _channelStates.update { current -> current - type }
    }

    fun reset() {
        _serviceRunning.value = false
        _channelStates.value = emptyMap()
    }

    data class ChannelState(
        val isRunning: Boolean,
        val connectedSince: Long? = null,
        val lastMessageAt: Long? = null,
        val error: String? = null,
        val messageCount: Int = 0
    )
}
