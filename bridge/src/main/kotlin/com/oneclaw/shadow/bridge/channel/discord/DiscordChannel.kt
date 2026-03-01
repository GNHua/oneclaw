package com.oneclaw.shadow.bridge.channel.discord

import android.content.Context
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
import com.oneclaw.shadow.bridge.image.BridgeImageStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class DiscordChannel(
    private val botToken: String,
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    preferences: BridgePreferences,
    conversationMapper: ConversationMapper,
    agentExecutor: BridgeAgentExecutor,
    messageObserver: BridgeMessageObserver,
    conversationManager: BridgeConversationManager,
    scope: CoroutineScope
) : MessagingChannel(
    channelType = ChannelType.DISCORD,
    preferences = preferences,
    conversationMapper = conversationMapper,
    agentExecutor = agentExecutor,
    messageObserver = messageObserver,
    conversationManager = conversationManager,
    scope = scope
) {
    private val imageStorage = BridgeImageStorage(context)
    private var gateway: DiscordGateway? = null
    private var running = false

    override suspend fun start() {
        running = true
        BridgeStateTracker.updateChannelState(
            ChannelType.DISCORD,
            BridgeStateTracker.ChannelState(isRunning = true, connectedSince = System.currentTimeMillis())
        )
        gateway = DiscordGateway(botToken, okHttpClient, ::onGatewayMessage, scope)
        scope.launch {
            var backoffMs = INITIAL_BACKOFF_MS
            while (running) {
                try {
                    gateway?.connect()
                    backoffMs = INITIAL_BACKOFF_MS
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Gateway connection error: ${e.message}")
                    BridgeStateTracker.updateChannelState(
                        ChannelType.DISCORD,
                        BridgeStateTracker.ChannelState(isRunning = true, error = e.message)
                    )
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    private suspend fun onGatewayMessage(data: JsonObject) {
        val authorId = data["author"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: return
        val isBot = data["author"]?.jsonObject?.get("bot")?.jsonPrimitive?.content?.toBoolean() ?: false
        if (isBot) return

        val channelId = data["channel_id"]?.jsonPrimitive?.content ?: return
        val content = data["content"]?.jsonPrimitive?.content ?: ""
        val messageId = data["id"]?.jsonPrimitive?.content ?: ""
        val authorUsername = data["author"]?.jsonObject?.get("username")?.jsonPrimitive?.content

        val imagePaths = mutableListOf<String>()
        data["attachments"]?.jsonArray?.forEach { attachment ->
            val url = attachment.jsonObject["url"]?.jsonPrimitive?.content ?: return@forEach
            val localPath = imageStorage.downloadAndStore(url)
            if (localPath != null) imagePaths.add(localPath)
        }

        if (content.isNotEmpty() || imagePaths.isNotEmpty()) {
            processInboundMessage(
                ChannelMessage(
                    externalChatId = channelId,
                    senderName = authorUsername,
                    senderId = authorId,
                    text = content.ifEmpty { "[Image]" },
                    imagePaths = imagePaths,
                    messageId = messageId
                )
            )
        }
    }

    override suspend fun stop() {
        running = false
        gateway?.disconnect()
        gateway = null
        BridgeStateTracker.removeChannelState(ChannelType.DISCORD)
    }

    override fun isRunning(): Boolean = running && gateway?.isConnected() == true

    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage) {
        try {
            val body = buildJsonObject { put("content", message.content) }
            val request = Request.Builder()
                .url("https://discord.com/api/v10/channels/$externalChatId/messages")
                .addHeader("Authorization", "Bot $botToken")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            okHttpClient.newCall(request).execute()
        } catch (e: Exception) {
            Log.e(TAG, "sendResponse error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "DiscordChannel"
        private const val INITIAL_BACKOFF_MS = 3_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
