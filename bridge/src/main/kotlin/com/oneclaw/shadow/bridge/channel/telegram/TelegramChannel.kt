package com.oneclaw.shadow.bridge.channel.telegram

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient

class TelegramChannel(
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
    channelType = ChannelType.TELEGRAM,
    preferences = preferences,
    conversationMapper = conversationMapper,
    agentExecutor = agentExecutor,
    messageObserver = messageObserver,
    conversationManager = conversationManager,
    scope = scope
) {
    private val api = TelegramApi(botToken, okHttpClient)
    private val htmlRenderer = TelegramHtmlRenderer()
    private val imageStorage = BridgeImageStorage(context)
    private var pollingJob: Job? = null
    private var running = false

    override suspend fun start() {
        running = true
        BridgeStateTracker.updateChannelState(
            ChannelType.TELEGRAM,
            BridgeStateTracker.ChannelState(isRunning = true, connectedSince = System.currentTimeMillis())
        )
        pollingJob = scope.launch {
            var backoffMs = INITIAL_BACKOFF_MS
            while (isActive) {
                try {
                    val offset = preferences.getTelegramUpdateOffset()
                    val updates = api.getUpdates(offset)
                    var maxUpdateId = offset - 1

                    for (update in updates) {
                        val updateId = update["update_id"]?.jsonPrimitive?.long ?: continue
                        if (updateId > maxUpdateId) maxUpdateId = updateId

                        val message = update["message"]?.jsonObject ?: continue
                        val chatId = message["chat"]?.jsonObject?.get("id")?.jsonPrimitive?.content ?: continue
                        val fromId = message["from"]?.jsonObject?.get("id")?.jsonPrimitive?.content
                        val fromFirstName = message["from"]?.jsonObject?.get("first_name")?.jsonPrimitive?.content
                        val text = message["text"]?.jsonPrimitive?.content ?: ""
                        val photos = message["photo"]?.jsonArray
                        val imagePaths = mutableListOf<String>()

                        if (photos != null) {
                            val largestPhoto = photos.lastOrNull()?.jsonObject
                            val fileId = largestPhoto?.get("file_id")?.jsonPrimitive?.content
                            if (fileId != null) {
                                val filePath = api.getFile(fileId)
                                if (filePath != null) {
                                    val downloadUrl = api.getFileDownloadUrl(filePath)
                                    val localPath = imageStorage.downloadAndStore(downloadUrl)
                                    if (localPath != null) imagePaths.add(localPath)
                                }
                            }
                        }

                        if (text.isNotEmpty() || imagePaths.isNotEmpty()) {
                            val channelMessage = ChannelMessage(
                                externalChatId = chatId,
                                senderName = fromFirstName,
                                senderId = fromId,
                                text = text.ifEmpty { "[Image]" },
                                imagePaths = imagePaths,
                                messageId = updateId.toString()
                            )
                            scope.launch { processInboundMessage(channelMessage) }
                        }
                    }

                    if (maxUpdateId >= offset) {
                        preferences.setTelegramUpdateOffset(maxUpdateId + 1)
                    }
                    backoffMs = INITIAL_BACKOFF_MS
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error: ${e.message}")
                    BridgeStateTracker.updateChannelState(
                        ChannelType.TELEGRAM,
                        BridgeStateTracker.ChannelState(isRunning = true, error = e.message)
                    )
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
    }

    override suspend fun stop() {
        running = false
        pollingJob?.cancel()
        pollingJob = null
        BridgeStateTracker.removeChannelState(ChannelType.TELEGRAM)
    }

    override fun isRunning(): Boolean = running && pollingJob?.isActive == true

    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage) {
        val htmlText = htmlRenderer.render(message.content)
        val parts = htmlRenderer.splitForTelegram(htmlText)
        parts.forEach { part ->
            api.sendMessage(chatId = externalChatId, text = part, parseMode = "HTML")
        }
    }

    override suspend fun sendTypingIndicator(externalChatId: String) {
        api.sendChatAction(chatId = externalChatId, action = "typing")
    }

    companion object {
        private const val TAG = "TelegramChannel"
        private const val INITIAL_BACKOFF_MS = 3_000L
        private const val MAX_BACKOFF_MS = 60_000L
    }
}
