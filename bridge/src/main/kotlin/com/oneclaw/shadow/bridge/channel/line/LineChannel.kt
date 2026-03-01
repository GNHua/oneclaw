package com.oneclaw.shadow.bridge.channel.line

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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

class LineChannel(
    private val channelAccessToken: String,
    private val channelSecret: String,
    private val port: Int,
    private val okHttpClient: OkHttpClient,
    preferences: BridgePreferences,
    conversationMapper: ConversationMapper,
    agentExecutor: BridgeAgentExecutor,
    messageObserver: BridgeMessageObserver,
    conversationManager: BridgeConversationManager,
    scope: CoroutineScope
) : MessagingChannel(
    channelType = ChannelType.LINE,
    preferences = preferences,
    conversationMapper = conversationMapper,
    agentExecutor = agentExecutor,
    messageObserver = messageObserver,
    conversationManager = conversationManager,
    scope = scope
) {
    private val api = LineApi(channelAccessToken, okHttpClient)
    private var webhookServer: LineWebhookServer? = null
    private var running = false

    override suspend fun start() {
        running = true
        try {
            val server = LineWebhookServer(port, channelSecret, ::onWebhookEvent, scope)
            server.start()
            webhookServer = server
            BridgeStateTracker.updateChannelState(
                ChannelType.LINE,
                BridgeStateTracker.ChannelState(isRunning = true, connectedSince = System.currentTimeMillis())
            )
        } catch (e: Exception) {
            running = false
            Log.e(TAG, "Failed to start LINE webhook server on port $port: ${e.message}")
            BridgeStateTracker.updateChannelState(
                ChannelType.LINE,
                BridgeStateTracker.ChannelState(isRunning = false, error = e.message)
            )
        }
    }

    private suspend fun onWebhookEvent(event: JsonObject) {
        val type = event["type"]?.jsonPrimitive?.content ?: return
        if (type != "message") return

        val messageObj = event["message"]?.jsonObject ?: return
        val msgType = messageObj["type"]?.jsonPrimitive?.content ?: return
        if (msgType != "text") return

        val source = event["source"]?.jsonObject ?: return
        val userId = source["userId"]?.jsonPrimitive?.content ?: return
        val text = messageObj["text"]?.jsonPrimitive?.content ?: return
        val messageId = messageObj["id"]?.jsonPrimitive?.content

        processInboundMessage(
            ChannelMessage(
                externalChatId = userId,
                senderName = null,
                senderId = userId,
                text = text,
                messageId = messageId
            )
        )
    }

    override suspend fun stop() {
        running = false
        webhookServer?.stop()
        webhookServer = null
        BridgeStateTracker.removeChannelState(ChannelType.LINE)
    }

    override fun isRunning(): Boolean = running && webhookServer?.isAlive == true

    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage) {
        api.pushMessage(externalChatId, message.content)
    }

    companion object {
        private const val TAG = "LineChannel"
    }
}
