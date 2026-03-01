package com.oneclaw.shadow.bridge.channel.webchat

import android.util.Log
import com.oneclaw.shadow.bridge.BridgeAgentExecutor
import com.oneclaw.shadow.bridge.BridgeConversationManager
import com.oneclaw.shadow.bridge.BridgeMessage
import com.oneclaw.shadow.bridge.BridgeMessageObserver
import com.oneclaw.shadow.bridge.BridgePreferences
import com.oneclaw.shadow.bridge.BridgeStateTracker
import com.oneclaw.shadow.bridge.channel.ChannelMessage
import com.oneclaw.shadow.bridge.channel.ChannelType
import com.oneclaw.shadow.bridge.channel.ConversationMapper
import com.oneclaw.shadow.bridge.channel.MessagingChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

class WebChatChannel(
    private val port: Int,
    private val accessToken: String?,
    preferences: BridgePreferences,
    conversationMapper: ConversationMapper,
    agentExecutor: BridgeAgentExecutor,
    messageObserver: BridgeMessageObserver,
    conversationManager: BridgeConversationManager,
    scope: CoroutineScope
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
    private var running = false
    private val messageCounter = AtomicInteger(0)

    override suspend fun start() {
        running = true
        try {
            val srv = WebChatServer(port, accessToken, ::onClientMessage, scope)
            srv.start()
            server = srv
            BridgeStateTracker.updateChannelState(
                ChannelType.WEBCHAT,
                BridgeStateTracker.ChannelState(isRunning = true, connectedSince = System.currentTimeMillis())
            )
        } catch (e: Exception) {
            running = false
            Log.e(TAG, "Failed to start WebChat server on port $port: ${e.message}")
            BridgeStateTracker.updateChannelState(
                ChannelType.WEBCHAT,
                BridgeStateTracker.ChannelState(isRunning = false, error = e.message)
            )
        }
    }

    private suspend fun onClientMessage(sessionId: String, text: String) {
        processInboundMessage(
            ChannelMessage(
                externalChatId = sessionId,
                senderName = null,
                senderId = sessionId,
                text = text,
                messageId = "$sessionId:${messageCounter.incrementAndGet()}"
            )
        )
    }

    override suspend fun stop() {
        running = false
        server?.stop()
        server = null
        BridgeStateTracker.removeChannelState(ChannelType.WEBCHAT)
    }

    override fun isRunning(): Boolean = running && server?.isAlive == true

    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage) {
        val responseJson = buildJsonObject {
            put("type", "response")
            put("text", message.content)
        }.toString()
        server?.sendToSession(externalChatId, responseJson)
    }

    override suspend fun sendTypingIndicator(externalChatId: String) {
        val typingJson = buildJsonObject { put("type", "typing") }.toString()
        server?.sendToSession(externalChatId, typingJson)
    }

    override suspend fun broadcast(message: BridgeMessage) {
        val responseJson = buildJsonObject {
            put("type", "response")
            put("text", message.content)
        }.toString()
        server?.sendToAll(responseJson)
    }

    companion object {
        private const val TAG = "WebChatChannel"
    }
}
