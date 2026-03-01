package com.oneclaw.shadow.bridge.channel

import com.oneclaw.shadow.bridge.BridgeAgentExecutor
import com.oneclaw.shadow.bridge.BridgeConversationManager
import com.oneclaw.shadow.bridge.BridgeMessage
import com.oneclaw.shadow.bridge.BridgeMessageObserver
import com.oneclaw.shadow.bridge.BridgePreferences
import com.oneclaw.shadow.bridge.BridgeStateTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

abstract class MessagingChannel(
    val channelType: ChannelType,
    protected val preferences: BridgePreferences,
    protected val conversationMapper: ConversationMapper,
    protected val agentExecutor: BridgeAgentExecutor,
    protected val messageObserver: BridgeMessageObserver,
    protected val conversationManager: BridgeConversationManager,
    protected val scope: CoroutineScope
) {
    private val deduplicationCache = object : LinkedHashMap<String, Boolean>(MAX_DEDUP_SIZE + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Boolean>): Boolean {
            return size > MAX_DEDUP_SIZE
        }
    }

    // --- Abstract: transport layer ---
    abstract suspend fun start()
    abstract suspend fun stop()
    abstract fun isRunning(): Boolean
    protected abstract suspend fun sendResponse(externalChatId: String, message: BridgeMessage)

    // --- Open: optional platform features ---
    protected open suspend fun sendTypingIndicator(externalChatId: String) {}
    open suspend fun broadcast(message: BridgeMessage) {
        val lastChatId = preferences.getLastChatId(channelType) ?: return
        runCatching { sendResponse(lastChatId, message) }
    }

    // --- Concrete: shared message pipeline ---
    protected suspend fun processInboundMessage(msg: ChannelMessage) {
        // 1. Deduplication
        val dedupKey = buildDedupKey(msg)
        synchronized(deduplicationCache) {
            if (deduplicationCache.containsKey(dedupKey)) return
            deduplicationCache[dedupKey] = true
        }

        // 2. Access control (whitelist check)
        if (!isUserAllowed(msg.senderId)) {
            return
        }

        // 3. Persist last chat ID for broadcast
        preferences.setLastChatId(channelType, msg.externalChatId)

        // 4. Handle /clear command
        if (msg.text.trim() == "/clear") {
            val newConversationId = conversationMapper.createNewConversation()
            val clearMessage = BridgeMessage(
                content = "Conversation cleared. Starting a new conversation.",
                timestamp = System.currentTimeMillis()
            )
            runCatching { sendResponse(msg.externalChatId, clearMessage) }
            updateChannelState(newMessage = true)
            return
        }

        // 5. Resolve conversation ID
        val conversationId = conversationMapper.resolveConversationId()

        // 6. Insert user message
        conversationManager.insertUserMessage(
            conversationId = conversationId,
            content = msg.text,
            imagePaths = msg.imagePaths
        )

        // 7. Execute agent
        val beforeTimestamp = System.currentTimeMillis()
        agentExecutor.executeMessage(
            conversationId = conversationId,
            userMessage = msg.text,
            imagePaths = msg.imagePaths
        )

        // 8. Launch typing indicator coroutine (every 4s)
        var typingJob: Job? = null
        typingJob = scope.launch {
            while (isActive) {
                runCatching { sendTypingIndicator(msg.externalChatId) }
                delay(TYPING_INTERVAL_MS)
            }
        }

        // 9. Await assistant response (300s timeout)
        val response = try {
            withTimeout(AGENT_RESPONSE_TIMEOUT_MS) {
                messageObserver.awaitNextAssistantMessage(
                    conversationId = conversationId,
                    afterTimestamp = beforeTimestamp
                )
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            BridgeMessage(
                content = "Sorry, the agent did not respond in time. Please try again.",
                timestamp = System.currentTimeMillis()
            )
        } finally {
            // 10. Cancel typing
            typingJob?.cancel()
        }

        // Send response
        runCatching { sendResponse(msg.externalChatId, response) }

        // Update state
        updateChannelState(newMessage = true)
    }

    private fun isUserAllowed(senderId: String?): Boolean {
        val allowedIds = getAllowedUserIds()
        if (allowedIds.isEmpty()) return true
        return senderId != null && allowedIds.contains(senderId)
    }

    private fun getAllowedUserIds(): Set<String> = when (channelType) {
        ChannelType.TELEGRAM -> preferences.getAllowedTelegramUserIds()
        ChannelType.DISCORD -> preferences.getAllowedDiscordUserIds()
        ChannelType.SLACK -> preferences.getAllowedSlackUserIds()
        ChannelType.MATRIX -> preferences.getAllowedMatrixUserIds()
        ChannelType.LINE -> preferences.getAllowedLineUserIds()
        ChannelType.WEBCHAT -> emptySet()
    }

    private fun buildDedupKey(msg: ChannelMessage): String =
        if (msg.messageId != null) {
            "${channelType.name}:${msg.messageId}"
        } else {
            "${channelType.name}:${msg.externalChatId}:${msg.text.hashCode()}"
        }

    private fun updateChannelState(newMessage: Boolean = false) {
        val current = BridgeStateTracker.channelStates.value[channelType]
        BridgeStateTracker.updateChannelState(
            channelType,
            BridgeStateTracker.ChannelState(
                isRunning = true,
                connectedSince = current?.connectedSince ?: System.currentTimeMillis(),
                lastMessageAt = if (newMessage) System.currentTimeMillis() else current?.lastMessageAt,
                error = null,
                messageCount = (current?.messageCount ?: 0) + if (newMessage) 1 else 0
            )
        )
    }

    companion object {
        private const val TAG = "MessagingChannel"
        private const val TYPING_INTERVAL_MS = 4000L
        private const val AGENT_RESPONSE_TIMEOUT_MS = 300_000L
        private const val MAX_DEDUP_SIZE = 500
    }
}
