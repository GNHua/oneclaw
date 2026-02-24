package com.tomandy.oneclaw.bridge.channel.line

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

class LineChannel(
    preferences: BridgePreferences,
    conversationMapper: ConversationMapper,
    agentExecutor: BridgeAgentExecutor,
    messageObserver: BridgeMessageObserver,
    conversationManager: BridgeConversationManager,
    scope: CoroutineScope,
    private val channelAccessToken: String,
    private val channelSecret: String,
    private val webhookPort: Int
) : MessagingChannel(
    channelType = ChannelType.LINE,
    preferences = preferences,
    conversationMapper = conversationMapper,
    agentExecutor = agentExecutor,
    messageObserver = messageObserver,
    conversationManager = conversationManager,
    scope = scope
) {
    private var webhookServer: LineWebhookServer? = null
    private var api: LineApi? = null

    override suspend fun start() {
        api = LineApi(channelAccessToken)

        webhookServer = LineWebhookServer(
            port = webhookPort,
            channelSecret = channelSecret,
            onWebhookEvent = ::handleWebhookEvent
        )
        webhookServer?.start()

        BridgeStateTracker.updateChannelState(
            ChannelType.LINE,
            BridgeStateTracker.ChannelState(
                isRunning = true,
                connectedSince = System.currentTimeMillis()
            )
        )
        Log.i(TAG, "LINE webhook server started on port $webhookPort")
    }

    private fun handleWebhookEvent(event: LineWebhookEvent) {
        if (event.type != "message") return

        val message = event.message ?: return
        if (message.type != "text") return

        val text = message.text ?: return
        if (text.isBlank()) return

        val userId = event.source?.userId ?: return

        // Access control
        val allowedUsers = preferences.getAllowedLineUserIds()
        if (allowedUsers.isNotEmpty() && userId !in allowedUsers) {
            Log.d(TAG, "Ignoring message from unauthorized LINE user: $userId")
            return
        }

        scope.launch {
            processInboundMessage(
                ChannelMessage(
                    externalChatId = userId,
                    senderName = null,
                    senderId = userId,
                    text = text
                )
            )
        }
    }

    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage) {
        api?.pushMessage(externalChatId, message.content)
    }

    override suspend fun stop() {
        webhookServer?.stop()
        webhookServer = null
        api?.shutdown()
        api = null
        BridgeStateTracker.removeChannelState(ChannelType.LINE)
    }

    override fun isRunning(): Boolean = webhookServer?.isAlive == true

    companion object {
        private const val TAG = "LineChannel"
    }
}
