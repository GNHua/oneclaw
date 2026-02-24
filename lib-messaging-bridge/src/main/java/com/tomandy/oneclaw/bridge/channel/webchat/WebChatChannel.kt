package com.tomandy.oneclaw.bridge.channel.webchat

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

class WebChatChannel(
    preferences: BridgePreferences,
    conversationMapper: ConversationMapper,
    agentExecutor: BridgeAgentExecutor,
    messageObserver: BridgeMessageObserver,
    conversationManager: BridgeConversationManager,
    scope: CoroutineScope,
    private val port: Int,
    private val accessToken: String?
) : MessagingChannel(
    channelType = ChannelType.WEBCHAT,
    preferences = preferences,
    conversationMapper = conversationMapper,
    agentExecutor = agentExecutor,
    messageObserver = messageObserver,
    conversationManager = conversationManager,
    scope = scope
) {
    private var server: WebChatServer? = null

    override suspend fun start() {
        server = WebChatServer(
            port = port,
            accessToken = accessToken,
            scope = scope,
            onMessage = { sessionId, text ->
                processInboundMessage(
                    ChannelMessage(
                        externalChatId = sessionId,
                        senderName = "WebChat",
                        senderId = sessionId,
                        text = text
                    )
                )
            }
        )
        server?.start(SOCKET_TIMEOUT, false)

        BridgeStateTracker.updateChannelState(
            ChannelType.WEBCHAT,
            BridgeStateTracker.ChannelState(
                isRunning = true,
                connectedSince = System.currentTimeMillis()
            )
        )
        Log.i(TAG, "WebChat server started on port $port")
    }

    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage) {
        server?.sendToSession(externalChatId, message.content)
    }

    override suspend fun stop() {
        server?.stop()
        server = null
        BridgeStateTracker.removeChannelState(ChannelType.WEBCHAT)
    }

    override fun isRunning(): Boolean = server?.isAlive == true

    companion object {
        private const val TAG = "WebChatChannel"
        private const val SOCKET_TIMEOUT = 30000
    }
}
