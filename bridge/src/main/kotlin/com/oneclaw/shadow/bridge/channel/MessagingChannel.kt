package com.oneclaw.shadow.bridge.channel

import android.util.Log
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
            BridgeStateTracker.emitNewSessionFromBridge(newConversationId)
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

        // 6. Launch typing indicator coroutine (every 4s) BEFORE agent execution
        val typingJob = scope.launch {
            while (isActive) {
                runCatching { sendTypingIndicator(msg.externalChatId) }
                delay(TYPING_INTERVAL_MS)
            }
        }

        // 7. Execute agent and get direct response
        val beforeTimestamp = System.currentTimeMillis()
        Log.d(TAG, "Executing agent for conv=$conversationId, text=${msg.text.take(50)}")
        val response = try {
            withTimeout(AGENT_RESPONSE_TIMEOUT_MS) {
                // executeMessage now returns the final response directly
                val directResponse = agentExecutor.executeMessage(
                    conversationId = conversationId,
                    userMessage = msg.text,
                    imagePaths = msg.imagePaths
                )
                Log.d(TAG, "executeMessage returned: ${if (directResponse != null) "content(${directResponse.content.length} chars)" else "null"}")
                // Use direct response; fall back to DB observer if null
                if (directResponse != null) {
                    directResponse
                } else {
                    Log.d(TAG, "Falling back to DB observer")
                    messageObserver.awaitNextAssistantMessage(
                        conversationId = conversationId,
                        afterTimestamp = beforeTimestamp,
                        timeoutMs = 10_000
                    )
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.w(TAG, "Agent timed out after ${AGENT_RESPONSE_TIMEOUT_MS}ms")
            BridgeMessage(
                content = "Sorry, the agent did not respond in time. Please try again.",
                timestamp = System.currentTimeMillis()
            )
        } finally {
            // 8. Cancel typing
            typingJob.cancel()
        }

        // Send response
        Log.d(TAG, "Sending response to ${msg.externalChatId}: ${response.content.length} chars")
        val sendResult = runCatching { sendResponse(msg.externalChatId, response) }
        if (sendResult.isFailure) {
            Log.e(TAG, "sendResponse FAILED", sendResult.exceptionOrNull())
        }

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
