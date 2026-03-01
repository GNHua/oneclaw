package com.oneclaw.shadow.feature.bridge

import com.oneclaw.shadow.bridge.BridgeStateTracker
import com.oneclaw.shadow.bridge.channel.ChannelType

data class BridgeSettingsUiState(
    val bridgeEnabled: Boolean = false,
    val serviceRunning: Boolean = false,
    val wakeLockEnabled: Boolean = false,

    // Telegram
    val telegramEnabled: Boolean = false,
    val telegramBotToken: String = "",
    val telegramAllowedUserIds: String = "",

    // Discord
    val discordEnabled: Boolean = false,
    val discordBotToken: String = "",
    val discordAllowedUserIds: String = "",

    // Slack
    val slackEnabled: Boolean = false,
    val slackBotToken: String = "",
    val slackAppToken: String = "",
    val slackAllowedUserIds: String = "",

    // Matrix
    val matrixEnabled: Boolean = false,
    val matrixAccessToken: String = "",
    val matrixHomeserver: String = "",
    val matrixAllowedUserIds: String = "",

    // LINE
    val lineEnabled: Boolean = false,
    val lineChannelAccessToken: String = "",
    val lineChannelSecret: String = "",
    val lineAllowedUserIds: String = "",
    val lineWebhookPort: Int = 8081,

    // WebChat
    val webChatEnabled: Boolean = false,
    val webChatAccessToken: String = "",
    val webChatPort: Int = 8080,

    // Runtime channel states
    val channelStates: Map<ChannelType, BridgeStateTracker.ChannelState> = emptyMap()
)
