package com.tomandy.oneclaw.bridge.channel.discord

import android.util.Log
import com.tomandy.oneclaw.bridge.BridgeAgentExecutor
import com.tomandy.oneclaw.bridge.BridgeConversationManager
import com.tomandy.oneclaw.bridge.BridgeMessage
import com.tomandy.oneclaw.bridge.BridgeMessageObserver
import com.tomandy.oneclaw.bridge.BridgePreferences
import com.tomandy.oneclaw.bridge.BridgeStateTracker
import com.tomandy.oneclaw.bridge.ChannelType
import com.tomandy.oneclaw.bridge.ConversationMapper
import com.tomandy.oneclaw.bridge.channel.BridgeImageStorage
import com.tomandy.oneclaw.bridge.channel.ChannelMessage
import com.tomandy.oneclaw.bridge.channel.MessagingChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

class DiscordChannel(
    preferences: BridgePreferences,
    conversationMapper: ConversationMapper,
    agentExecutor: BridgeAgentExecutor,
    messageObserver: BridgeMessageObserver,
    conversationManager: BridgeConversationManager,
    scope: CoroutineScope,
    private val botToken: String,
    private val imageDir: File
) : MessagingChannel(
    channelType = ChannelType.DISCORD,
    preferences = preferences,
    conversationMapper = conversationMapper,
    agentExecutor = agentExecutor,
    messageObserver = messageObserver,
    conversationManager = conversationManager,
    scope = scope
) {
    private var gateway: DiscordGateway? = null
    private var api: DiscordApi? = null

    override suspend fun start() {
        api = DiscordApi(botToken)
        gateway = DiscordGateway(
            botToken = botToken,
            scope = scope,
            onMessage = ::handleGatewayMessage
        )
        gateway?.connect()

        BridgeStateTracker.updateChannelState(
            ChannelType.DISCORD,
            BridgeStateTracker.ChannelState(
                isRunning = true,
                connectedSince = System.currentTimeMillis()
            )
        )
    }

    private suspend fun handleGatewayMessage(event: MessageCreateEvent) {
        if (event.author.bot) return

        val userId = event.author.id
        val allowedUsers = preferences.getAllowedDiscordUserIds()
        if (allowedUsers.isNotEmpty() && userId !in allowedUsers) {
            Log.d(TAG, "Ignoring message from unauthorized user: $userId")
            return
        }

        val text = event.content
        val imageAttachments = event.attachments.filter {
            it.contentType?.startsWith("image/") == true
        }

        // Skip messages with no text and no images
        if (text.isBlank() && imageAttachments.isEmpty()) return

        // Download image attachments
        val httpClient = api?.client
        val imagePaths = if (httpClient != null && imageAttachments.isNotEmpty()) {
            imageAttachments.mapNotNull { attachment ->
                try {
                    BridgeImageStorage.downloadImage(httpClient, attachment.url, imageDir)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download Discord attachment: ${attachment.filename}", e)
                    null
                }
            }
        } else {
            emptyList()
        }

        scope.launch {
            processInboundMessage(
                ChannelMessage(
                    externalChatId = event.channelId,
                    senderName = event.author.username,
                    senderId = userId,
                    text = text,
                    imagePaths = imagePaths,
                    messageId = event.id
                )
            )
        }
    }

    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage) {
        api?.sendLongMessage(externalChatId, message.content)
    }

    override suspend fun stop() {
        gateway?.disconnect()
        gateway = null
        api?.shutdown()
        api = null
        BridgeStateTracker.removeChannelState(ChannelType.DISCORD)
    }

    override fun isRunning(): Boolean = gateway?.isConnected == true

    companion object {
        private const val TAG = "DiscordChannel"
    }
}
