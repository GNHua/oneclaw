package com.tomandy.oneclaw.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tomandy.oneclaw.bridge.BridgePreferences
import com.tomandy.oneclaw.bridge.BridgeStateTracker
import com.tomandy.oneclaw.bridge.service.BridgeCredentialProvider
import com.tomandy.oneclaw.bridge.service.MessagingBridgeService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MessagingBridgeViewModel(
    private val context: Context
) : ViewModel() {

    private val preferences = BridgePreferences(context)
    private val credentials = BridgeCredentialProvider(context)

    val channelStates = BridgeStateTracker.channelStates
    val serviceRunning = BridgeStateTracker.serviceRunning

    // Telegram state
    private val _telegramEnabled = MutableStateFlow(preferences.isTelegramEnabled())
    val telegramEnabled: StateFlow<Boolean> = _telegramEnabled.asStateFlow()

    private val _telegramBotToken = MutableStateFlow(credentials.getTelegramBotToken() ?: "")
    val telegramBotToken: StateFlow<String> = _telegramBotToken.asStateFlow()

    private val _telegramAllowedUsers = MutableStateFlow(
        preferences.getAllowedTelegramUserIds().joinToString(", ")
    )
    val telegramAllowedUsers: StateFlow<String> = _telegramAllowedUsers.asStateFlow()

    // Discord state
    private val _discordEnabled = MutableStateFlow(preferences.isDiscordEnabled())
    val discordEnabled: StateFlow<Boolean> = _discordEnabled.asStateFlow()

    private val _discordBotToken = MutableStateFlow(credentials.getDiscordBotToken() ?: "")
    val discordBotToken: StateFlow<String> = _discordBotToken.asStateFlow()

    private val _discordAllowedUsers = MutableStateFlow(
        preferences.getAllowedDiscordUserIds().joinToString(", ")
    )
    val discordAllowedUsers: StateFlow<String> = _discordAllowedUsers.asStateFlow()

    // WebChat state
    private val _webChatEnabled = MutableStateFlow(preferences.isWebChatEnabled())
    val webChatEnabled: StateFlow<Boolean> = _webChatEnabled.asStateFlow()

    private val _webChatPort = MutableStateFlow(preferences.getWebChatPort().toString())
    val webChatPort: StateFlow<String> = _webChatPort.asStateFlow()

    private val _webChatAccessToken = MutableStateFlow(credentials.getWebChatAccessToken() ?: "")
    val webChatAccessToken: StateFlow<String> = _webChatAccessToken.asStateFlow()

    // Save status
    private val _saveStatus = MutableStateFlow<String?>(null)
    val saveStatus: StateFlow<String?> = _saveStatus.asStateFlow()

    fun updateTelegramBotToken(token: String) {
        _telegramBotToken.value = token
    }

    fun updateTelegramAllowedUsers(users: String) {
        _telegramAllowedUsers.value = users
    }

    fun updateDiscordBotToken(token: String) {
        _discordBotToken.value = token
    }

    fun updateDiscordAllowedUsers(users: String) {
        _discordAllowedUsers.value = users
    }

    fun updateWebChatPort(port: String) {
        _webChatPort.value = port
    }

    fun updateWebChatAccessToken(token: String) {
        _webChatAccessToken.value = token
    }

    fun saveTelegramConfig() {
        viewModelScope.launch {
            val token = _telegramBotToken.value.trim()
            if (token.isNotBlank()) {
                credentials.saveTelegramBotToken(token)
            }
            val userIds = _telegramAllowedUsers.value
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
            preferences.setAllowedTelegramUserIds(userIds)
            _saveStatus.value = "Telegram config saved"
        }
    }

    fun saveDiscordConfig() {
        viewModelScope.launch {
            val token = _discordBotToken.value.trim()
            if (token.isNotBlank()) {
                credentials.saveDiscordBotToken(token)
            }
            val userIds = _discordAllowedUsers.value
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toSet()
            preferences.setAllowedDiscordUserIds(userIds)
            _saveStatus.value = "Discord config saved"
        }
    }

    fun saveWebChatConfig() {
        viewModelScope.launch {
            val port = _webChatPort.value.toIntOrNull() ?: 8080
            preferences.setWebChatPort(port)
            val token = _webChatAccessToken.value.trim()
            if (token.isNotBlank()) {
                credentials.saveWebChatAccessToken(token)
            }
            _saveStatus.value = "WebChat config saved"
        }
    }

    fun setTelegramEnabled(enabled: Boolean) {
        _telegramEnabled.value = enabled
        preferences.setTelegramEnabled(enabled)
        restartServiceIfNeeded()
    }

    fun setDiscordEnabled(enabled: Boolean) {
        _discordEnabled.value = enabled
        preferences.setDiscordEnabled(enabled)
        restartServiceIfNeeded()
    }

    fun setWebChatEnabled(enabled: Boolean) {
        _webChatEnabled.value = enabled
        preferences.setWebChatEnabled(enabled)
        restartServiceIfNeeded()
    }

    fun startBridge() {
        MessagingBridgeService.start(context)
    }

    fun stopBridge() {
        MessagingBridgeService.stop(context)
    }

    private fun restartServiceIfNeeded() {
        val anyEnabled = _telegramEnabled.value || _discordEnabled.value || _webChatEnabled.value
        if (anyEnabled) {
            MessagingBridgeService.start(context)
        } else {
            MessagingBridgeService.stop(context)
        }
    }

    fun clearSaveStatus() {
        _saveStatus.value = null
    }
}
