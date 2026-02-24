package com.tomandy.oneclaw.bridge.channel.slack

import android.util.Log
import com.tomandy.oneclaw.bridge.BridgeAgentExecutor
import com.tomandy.oneclaw.bridge.BridgeConversationManager
import com.tomandy.oneclaw.bridge.BridgeMessage
import com.tomandy.oneclaw.bridge.BridgeMessageObserver
import com.tomandy.oneclaw.bridge.BridgePreferences
import com.tomandy.oneclaw.bridge.BridgeStateTracker
import com.tomandy.oneclaw.bridge.ChannelType
import com.tomandy.oneclaw.bridge.ConversationMapper
import com.tomandy.oneclaw.bridge.channel.ChannelMessage
import com.tomandy.oneclaw.bridge.channel.MessagingChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SlackChannel(
    preferences: BridgePreferences,
    conversationMapper: ConversationMapper,
    agentExecutor: BridgeAgentExecutor,
    messageObserver: BridgeMessageObserver,
    conversationManager: BridgeConversationManager,
    scope: CoroutineScope,
    private val botToken: String,
    private val appToken: String
) : MessagingChannel(
    channelType = ChannelType.SLACK,
    preferences = preferences,
    conversationMapper = conversationMapper,
    agentExecutor = agentExecutor,
    messageObserver = messageObserver,
    conversationManager = conversationManager,
    scope = scope
) {
    private var socketMode: SlackSocketMode? = null
    private var api: SlackApi? = null

    override suspend fun start() {
        api = SlackApi(botToken)
        socketMode = SlackSocketMode(
            appToken = appToken,
            scope = scope,
            onMessage = ::handleSlackEvent
        )
        socketMode?.connect()

        BridgeStateTracker.updateChannelState(
            ChannelType.SLACK,
            BridgeStateTracker.ChannelState(
                isRunning = true,
                connectedSince = System.currentTimeMillis()
            )
        )
    }

    private suspend fun handleSlackEvent(event: SlackEvent) {
        val userId = event.user ?: return
        val channelId = event.channel ?: return
        val text = event.text ?: return

        if (text.isBlank()) return

        // Access control
        val allowedUsers = preferences.getAllowedSlackUserIds()
        if (allowedUsers.isNotEmpty() && userId !in allowedUsers) {
            Log.d(TAG, "Ignoring message from unauthorized Slack user: $userId")
            return
        }

        scope.launch {
            processInboundMessage(
                ChannelMessage(
                    externalChatId = channelId,
                    senderName = null,
                    senderId = userId,
                    text = text,
                    messageId = event.ts?.let { "${channelId}_$it" }
                )
            )
        }
    }

    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage) {
        api?.sendLongMessage(externalChatId, message.content)
    }

    override suspend fun stop() {
        socketMode?.disconnect()
        socketMode = null
        api?.shutdown()
        api = null
        BridgeStateTracker.removeChannelState(ChannelType.SLACK)
    }

    override fun isRunning(): Boolean = socketMode?.isConnected == true

    companion object {
        private const val TAG = "SlackChannel"
    }
}
