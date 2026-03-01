package com.oneclaw.shadow.feature.bridge

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneclaw.shadow.bridge.BridgePreferences
import com.oneclaw.shadow.bridge.BridgeStateTracker
import com.oneclaw.shadow.bridge.service.BridgeCredentialProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update

class BridgeSettingsViewModel(
    private val preferences: BridgePreferences,
    private val credentialProvider: BridgeCredentialProvider
) : ViewModel() {

    private val _uiState = MutableStateFlow(loadInitialState())
    val uiState: StateFlow<BridgeSettingsUiState> = _uiState.asStateFlow()

    init {
        BridgeStateTracker.serviceRunning
            .onEach { running ->
                _uiState.update { it.copy(serviceRunning = running) }
            }
            .launchIn(viewModelScope)

        BridgeStateTracker.channelStates
            .onEach { states ->
                _uiState.update { it.copy(channelStates = states) }
            }
            .launchIn(viewModelScope)
    }

    private fun loadInitialState(): BridgeSettingsUiState {
        return BridgeSettingsUiState(
            bridgeEnabled = preferences.isBridgeEnabled(),
            serviceRunning = BridgeStateTracker.serviceRunning.value,
            wakeLockEnabled = preferences.isWakeLockEnabled(),

            telegramEnabled = preferences.isTelegramEnabled(),
            telegramBotToken = credentialProvider.getTelegramBotToken() ?: "",
            telegramAllowedUserIds = preferences.getAllowedTelegramUserIds()
                .joinToString(", "),

            discordEnabled = preferences.isDiscordEnabled(),
            discordBotToken = credentialProvider.getDiscordBotToken() ?: "",
            discordAllowedUserIds = preferences.getAllowedDiscordUserIds()
                .joinToString(", "),

            slackEnabled = preferences.isSlackEnabled(),
            slackBotToken = credentialProvider.getSlackBotToken() ?: "",
            slackAppToken = credentialProvider.getSlackAppToken() ?: "",
            slackAllowedUserIds = preferences.getAllowedSlackUserIds()
                .joinToString(", "),

            matrixEnabled = preferences.isMatrixEnabled(),
            matrixAccessToken = credentialProvider.getMatrixAccessToken() ?: "",
            matrixHomeserver = preferences.getMatrixHomeserver(),
            matrixAllowedUserIds = preferences.getAllowedMatrixUserIds()
                .joinToString(", "),

            lineEnabled = preferences.isLineEnabled(),
            lineChannelAccessToken = credentialProvider.getLineChannelAccessToken() ?: "",
            lineChannelSecret = credentialProvider.getLineChannelSecret() ?: "",
            lineAllowedUserIds = preferences.getAllowedLineUserIds()
                .joinToString(", "),
            lineWebhookPort = preferences.getLineWebhookPort(),

            webChatEnabled = preferences.isWebChatEnabled(),
            webChatAccessToken = credentialProvider.getWebChatAccessToken() ?: "",
            webChatPort = preferences.getWebChatPort(),

            channelStates = BridgeStateTracker.channelStates.value
        )
    }

    // Master toggle
    fun toggleBridge(enabled: Boolean) {
        preferences.setBridgeEnabled(enabled)
        _uiState.update { it.copy(bridgeEnabled = enabled) }
    }

    fun toggleWakeLock(enabled: Boolean) {
        preferences.setWakeLockEnabled(enabled)
        _uiState.update { it.copy(wakeLockEnabled = enabled) }
    }

    // Telegram
    fun toggleTelegram(enabled: Boolean) {
        preferences.setTelegramEnabled(enabled)
        _uiState.update { it.copy(telegramEnabled = enabled) }
    }

    fun updateTelegramBotToken(token: String) {
        credentialProvider.saveTelegramBotToken(token)
        _uiState.update { it.copy(telegramBotToken = token) }
    }

    fun updateTelegramAllowedUserIds(ids: String) {
        val parsed = parseUserIds(ids)
        preferences.setAllowedTelegramUserIds(parsed)
        _uiState.update { it.copy(telegramAllowedUserIds = ids) }
    }

    // Discord
    fun toggleDiscord(enabled: Boolean) {
        preferences.setDiscordEnabled(enabled)
        _uiState.update { it.copy(discordEnabled = enabled) }
    }

    fun updateDiscordBotToken(token: String) {
        credentialProvider.saveDiscordBotToken(token)
        _uiState.update { it.copy(discordBotToken = token) }
    }

    fun updateDiscordAllowedUserIds(ids: String) {
        val parsed = parseUserIds(ids)
        preferences.setAllowedDiscordUserIds(parsed)
        _uiState.update { it.copy(discordAllowedUserIds = ids) }
    }

    // Slack
    fun toggleSlack(enabled: Boolean) {
        preferences.setSlackEnabled(enabled)
        _uiState.update { it.copy(slackEnabled = enabled) }
    }

    fun updateSlackBotToken(token: String) {
        credentialProvider.saveSlackBotToken(token)
        _uiState.update { it.copy(slackBotToken = token) }
    }

    fun updateSlackAppToken(token: String) {
        credentialProvider.saveSlackAppToken(token)
        _uiState.update { it.copy(slackAppToken = token) }
    }

    fun updateSlackAllowedUserIds(ids: String) {
        val parsed = parseUserIds(ids)
        preferences.setAllowedSlackUserIds(parsed)
        _uiState.update { it.copy(slackAllowedUserIds = ids) }
    }

    // Matrix
    fun toggleMatrix(enabled: Boolean) {
        preferences.setMatrixEnabled(enabled)
        _uiState.update { it.copy(matrixEnabled = enabled) }
    }

    fun updateMatrixAccessToken(token: String) {
        credentialProvider.saveMatrixAccessToken(token)
        _uiState.update { it.copy(matrixAccessToken = token) }
    }

    fun updateMatrixHomeserver(homeserver: String) {
        preferences.setMatrixHomeserver(homeserver)
        _uiState.update { it.copy(matrixHomeserver = homeserver) }
    }

    fun updateMatrixAllowedUserIds(ids: String) {
        val parsed = parseUserIds(ids)
        preferences.setAllowedMatrixUserIds(parsed)
        _uiState.update { it.copy(matrixAllowedUserIds = ids) }
    }

    // LINE
    fun toggleLine(enabled: Boolean) {
        preferences.setLineEnabled(enabled)
        _uiState.update { it.copy(lineEnabled = enabled) }
    }

    fun updateLineChannelAccessToken(token: String) {
        credentialProvider.saveLineChannelAccessToken(token)
        _uiState.update { it.copy(lineChannelAccessToken = token) }
    }

    fun updateLineChannelSecret(secret: String) {
        credentialProvider.saveLineChannelSecret(secret)
        _uiState.update { it.copy(lineChannelSecret = secret) }
    }

    fun updateLineAllowedUserIds(ids: String) {
        val parsed = parseUserIds(ids)
        preferences.setAllowedLineUserIds(parsed)
        _uiState.update { it.copy(lineAllowedUserIds = ids) }
    }

    fun updateLineWebhookPort(port: Int) {
        preferences.setLineWebhookPort(port)
        _uiState.update { it.copy(lineWebhookPort = port) }
    }

    // WebChat
    fun toggleWebChat(enabled: Boolean) {
        preferences.setWebChatEnabled(enabled)
        _uiState.update { it.copy(webChatEnabled = enabled) }
    }

    fun updateWebChatAccessToken(token: String) {
        credentialProvider.saveWebChatAccessToken(token)
        _uiState.update { it.copy(webChatAccessToken = token) }
    }

    fun updateWebChatPort(port: Int) {
        preferences.setWebChatPort(port)
        _uiState.update { it.copy(webChatPort = port) }
    }

    private fun parseUserIds(ids: String): Set<String> {
        if (ids.isBlank()) return emptySet()
        return ids.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }
}
