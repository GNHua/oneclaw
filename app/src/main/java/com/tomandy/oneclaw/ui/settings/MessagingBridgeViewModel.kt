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

    // Slack state
    private val _slackEnabled = MutableStateFlow(preferences.isSlackEnabled())
    val slackEnabled: StateFlow<Boolean> = _slackEnabled.asStateFlow()

    private val _slackBotToken = MutableStateFlow(credentials.getSlackBotToken() ?: "")
    val slackBotToken: StateFlow<String> = _slackBotToken.asStateFlow()

    private val _slackAppToken = MutableStateFlow(credentials.getSlackAppToken() ?: "")
    val slackAppToken: StateFlow<String> = _slackAppToken.asStateFlow()

    private val _slackAllowedUsers = MutableStateFlow(
        preferences.getAllowedSlackUserIds().joinToString(", ")
    )
    val slackAllowedUsers: StateFlow<String> = _slackAllowedUsers.asStateFlow()

    // Matrix state
    private val _matrixEnabled = MutableStateFlow(preferences.isMatrixEnabled())
    val matrixEnabled: StateFlow<Boolean> = _matrixEnabled.asStateFlow()

    private val _matrixHomeserver = MutableStateFlow(preferences.getMatrixHomeserver())
    val matrixHomeserver: StateFlow<String> = _matrixHomeserver.asStateFlow()

    private val _matrixAccessToken = MutableStateFlow(credentials.getMatrixAccessToken() ?: "")
    val matrixAccessToken: StateFlow<String> = _matrixAccessToken.asStateFlow()

    private val _matrixAllowedUsers = MutableStateFlow(
        preferences.getAllowedMatrixUserIds().joinToString(", ")
    )
    val matrixAllowedUsers: StateFlow<String> = _matrixAllowedUsers.asStateFlow()

    // LINE state
    private val _lineEnabled = MutableStateFlow(preferences.isLineEnabled())
    val lineEnabled: StateFlow<Boolean> = _lineEnabled.asStateFlow()

    private val _lineChannelAccessToken = MutableStateFlow(credentials.getLineChannelAccessToken() ?: "")
    val lineChannelAccessToken: StateFlow<String> = _lineChannelAccessToken.asStateFlow()

    private val _lineChannelSecret = MutableStateFlow(credentials.getLineChannelSecret() ?: "")
    val lineChannelSecret: StateFlow<String> = _lineChannelSecret.asStateFlow()

    private val _lineAllowedUsers = MutableStateFlow(
        preferences.getAllowedLineUserIds().joinToString(", ")
    )
    val lineAllowedUsers: StateFlow<String> = _lineAllowedUsers.asStateFlow()

    // Wake lock
    private val _wakeLockEnabled = MutableStateFlow(preferences.isWakeLockEnabled())
    val wakeLockEnabled: StateFlow<Boolean> = _wakeLockEnabled.asStateFlow()

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

    fun updateSlackBotToken(token: String) { _slackBotToken.value = token }
    fun updateSlackAppToken(token: String) { _slackAppToken.value = token }
    fun updateSlackAllowedUsers(users: String) { _slackAllowedUsers.value = users }

    fun updateMatrixHomeserver(url: String) { _matrixHomeserver.value = url }
    fun updateMatrixAccessToken(token: String) { _matrixAccessToken.value = token }
    fun updateMatrixAllowedUsers(users: String) { _matrixAllowedUsers.value = users }

    fun updateLineChannelAccessToken(token: String) { _lineChannelAccessToken.value = token }
    fun updateLineChannelSecret(secret: String) { _lineChannelSecret.value = secret }
    fun updateLineAllowedUsers(users: String) { _lineAllowedUsers.value = users }

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

    fun saveSlackConfig() {
        viewModelScope.launch {
            val botToken = _slackBotToken.value.trim()
            if (botToken.isNotBlank()) credentials.saveSlackBotToken(botToken)
            val appToken = _slackAppToken.value.trim()
            if (appToken.isNotBlank()) credentials.saveSlackAppToken(appToken)
            val userIds = _slackAllowedUsers.value
                .split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
            preferences.setAllowedSlackUserIds(userIds)
            _saveStatus.value = "Slack config saved"
        }
    }

    fun saveMatrixConfig() {
        viewModelScope.launch {
            preferences.setMatrixHomeserver(_matrixHomeserver.value.trim())
            val token = _matrixAccessToken.value.trim()
            if (token.isNotBlank()) credentials.saveMatrixAccessToken(token)
            val userIds = _matrixAllowedUsers.value
                .split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
            preferences.setAllowedMatrixUserIds(userIds)
            _saveStatus.value = "Matrix config saved"
        }
    }

    fun saveLineConfig() {
        viewModelScope.launch {
            val accessToken = _lineChannelAccessToken.value.trim()
            if (accessToken.isNotBlank()) credentials.saveLineChannelAccessToken(accessToken)
            val secret = _lineChannelSecret.value.trim()
            if (secret.isNotBlank()) credentials.saveLineChannelSecret(secret)
            val userIds = _lineAllowedUsers.value
                .split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
            preferences.setAllowedLineUserIds(userIds)
            _saveStatus.value = "LINE config saved"
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

    fun setSlackEnabled(enabled: Boolean) {
        _slackEnabled.value = enabled
        preferences.setSlackEnabled(enabled)
        restartServiceIfNeeded()
    }

    fun setMatrixEnabled(enabled: Boolean) {
        _matrixEnabled.value = enabled
        preferences.setMatrixEnabled(enabled)
        restartServiceIfNeeded()
    }

    fun setLineEnabled(enabled: Boolean) {
        _lineEnabled.value = enabled
        preferences.setLineEnabled(enabled)
        restartServiceIfNeeded()
    }

    fun setWakeLockEnabled(enabled: Boolean) {
        _wakeLockEnabled.value = enabled
        preferences.setWakeLockEnabled(enabled)
        restartServiceIfNeeded()
    }

    fun startBridge() {
        MessagingBridgeService.start(context)
    }

    fun stopBridge() {
        MessagingBridgeService.stop(context)
    }

    private fun restartServiceIfNeeded() {
        val anyEnabled = _telegramEnabled.value || _discordEnabled.value || _webChatEnabled.value ||
            _slackEnabled.value || _matrixEnabled.value || _lineEnabled.value
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
