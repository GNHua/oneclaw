package com.oneclaw.shadow.feature.chat.usecase

import com.oneclaw.shadow.core.model.AiModel
import com.oneclaw.shadow.core.model.Agent
import com.oneclaw.shadow.core.model.Message
import com.oneclaw.shadow.core.model.MessageType
import com.oneclaw.shadow.core.model.Provider
import com.oneclaw.shadow.core.model.ToolCallStatus
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.core.repository.AgentRepository
import com.oneclaw.shadow.core.repository.MessageRepository
import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.core.repository.SessionRepository
import com.oneclaw.shadow.core.util.ErrorCode
import com.oneclaw.shadow.core.util.ToolResultTruncator
import com.oneclaw.shadow.data.remote.adapter.ModelApiAdapterFactory
import com.oneclaw.shadow.feature.memory.injection.MemoryInjector
import com.oneclaw.shadow.data.remote.adapter.StreamEvent
import com.oneclaw.shadow.data.security.ApiKeyStorage
import com.oneclaw.shadow.feature.chat.ChatEvent
import com.oneclaw.shadow.tool.engine.ToolCallRequest
import com.oneclaw.shadow.tool.engine.ToolExecutionEngine
import com.oneclaw.shadow.tool.engine.ToolRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class SendMessageUseCase(
    private val agentRepository: AgentRepository,
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val providerRepository: ProviderRepository,
    private val apiKeyStorage: ApiKeyStorage,
    private val adapterFactory: ModelApiAdapterFactory,
    private val toolExecutionEngine: ToolExecutionEngine,
    private val toolRegistry: ToolRegistry,
    private val autoCompactUseCase: AutoCompactUseCase,
    private val memoryInjector: MemoryInjector? = null
) {
    companion object {
        const val MAX_TOOL_ROUNDS = 100
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun execute(
        sessionId: String,
        userText: String,
        agentId: String,
        pendingMessages: Channel<String> = Channel(Channel.UNLIMITED)
    ): Flow<ChatEvent> = channelFlow {

        // 1. Resolve agent
        val agent = agentRepository.getAgentById(agentId) ?: run {
            send(ChatEvent.Error("Agent not found.", ErrorCode.VALIDATION_ERROR, false))
            return@channelFlow
        }

        // 2. Resolve model + provider
        val (model, provider) = resolveModel(agent) ?: run {
            send(ChatEvent.Error(
                "No model configured. Please set up a provider in Settings.",
                ErrorCode.VALIDATION_ERROR, false
            ))
            return@channelFlow
        }

        val apiKey = apiKeyStorage.getApiKey(provider.id) ?: run {
            send(ChatEvent.Error(
                "API key not configured for ${provider.name}.",
                ErrorCode.AUTH_ERROR, false
            ))
            return@channelFlow
        }

        // 3. Save user message
        val userMessage = Message(
            id = "", sessionId = sessionId, type = MessageType.USER,
            content = userText, thinkingContent = null,
            toolCallId = null, toolName = null, toolInput = null, toolOutput = null,
            toolStatus = null, toolDurationMs = null, tokenCountInput = null,
            tokenCountOutput = null, modelId = null, providerId = null, createdAt = 0
        )
        messageRepository.addMessage(userMessage)

        // 4. Update session stats
        sessionRepository.updateMessageStats(
            id = sessionId,
            count = messageRepository.getMessageCount(sessionId),
            preview = userText.take(100)
        )
        sessionRepository.setActive(sessionId, true)

        // 5. Get agent tools
        val agentToolDefs: List<ToolDefinition>? = if (agent.toolIds.isNotEmpty()) {
            toolRegistry.getToolDefinitionsByNames(agent.toolIds).takeIf { it.isNotEmpty() }
        } else null

        var round = 0
        try {
            // Inject memory context into system prompt (only on first round)
            val baseSystemPrompt = if (memoryInjector != null) {
                try {
                    val memoryInjection = memoryInjector.buildInjection(query = userText)
                    if (memoryInjection.isNotBlank()) {
                        if (agent.systemPrompt.isBlank()) memoryInjection
                        else "${agent.systemPrompt}\n\n$memoryInjection"
                    } else {
                        agent.systemPrompt
                    }
                } catch (e: Exception) {
                    // Memory injection failure is non-fatal -- proceed without it
                    agent.systemPrompt
                }
            } else {
                agent.systemPrompt
            }

            while (round < MAX_TOOL_ROUNDS) {
                val allMessages = messageRepository.getMessagesSnapshot(sessionId)
                val session = sessionRepository.getSessionById(sessionId)!!
                val (effectiveSystemPrompt, apiMessages) = CompactAwareMessageBuilder.build(
                    session = session,
                    allMessages = allMessages,
                    originalSystemPrompt = baseSystemPrompt
                )
                val adapter = adapterFactory.getAdapter(provider.type)

                var accumulatedText = ""
                var accumulatedThinking = ""
                val pendingToolCalls = mutableListOf<PendingToolCall>()
                var usage: ChatEvent.TokenUsage? = null

                adapter.sendMessageStream(
                    apiBaseUrl = provider.apiBaseUrl,
                    apiKey = apiKey,
                    modelId = model.id,
                    messages = apiMessages,
                    tools = agentToolDefs,
                    systemPrompt = effectiveSystemPrompt
                ).collect { event ->
                    when (event) {
                        is StreamEvent.TextDelta -> {
                            accumulatedText += event.text
                            send(ChatEvent.StreamingText(event.text))
                        }
                        is StreamEvent.ThinkingDelta -> {
                            accumulatedThinking += event.text
                            send(ChatEvent.ThinkingText(event.text))
                        }
                        is StreamEvent.ToolCallStart -> {
                            pendingToolCalls.add(PendingToolCall(
                                id = event.toolCallId, name = event.toolName, arguments = StringBuilder()
                            ))
                            send(ChatEvent.ToolCallStarted(event.toolCallId, event.toolName))
                        }
                        is StreamEvent.ToolCallDelta -> {
                            pendingToolCalls.find { it.id == event.toolCallId }
                                ?.arguments?.append(event.argumentsDelta)
                            send(ChatEvent.ToolCallArgumentsDelta(event.toolCallId, event.argumentsDelta))
                        }
                        is StreamEvent.ToolCallEnd -> { /* handled after stream completes */ }
                        is StreamEvent.Usage -> {
                            usage = ChatEvent.TokenUsage(event.inputTokens, event.outputTokens)
                        }
                        is StreamEvent.Error -> throw ApiException(event.message, event.code)
                        is StreamEvent.Done -> { /* stream complete */ }
                    }
                }

                // Save AI response
                val aiMessage = messageRepository.addMessage(Message(
                    id = "", sessionId = sessionId, type = MessageType.AI_RESPONSE,
                    content = accumulatedText,
                    thinkingContent = accumulatedThinking.ifEmpty { null },
                    toolCallId = null, toolName = null, toolInput = null, toolOutput = null,
                    toolStatus = null, toolDurationMs = null,
                    tokenCountInput = usage?.inputTokens, tokenCountOutput = usage?.outputTokens,
                    modelId = model.id, providerId = provider.id, createdAt = 0
                ))

                if (pendingToolCalls.isEmpty()) {
                    // Drain any pending user messages before deciding to stop
                    val injected = drainPendingMessages(pendingMessages)
                    for (text in injected) {
                        send(ChatEvent.UserMessageInjected(text))
                    }
                    if (injected.isEmpty()) {
                        // No pending messages -- loop is truly done
                        sessionRepository.updateMessageStats(
                            id = sessionId,
                            count = messageRepository.getMessageCount(sessionId),
                            preview = accumulatedText.take(100)
                        )
                        send(ChatEvent.ResponseComplete(aiMessage, usage))

                        // Trigger auto-compact check
                        send(ChatEvent.CompactStarted)
                        val compactResult = autoCompactUseCase.compactIfNeeded(sessionId, model, provider)
                        send(ChatEvent.CompactCompleted(compactResult.didCompact))

                        break
                    }
                    // Pending messages found -- continue to next iteration
                    round++
                    if (round < MAX_TOOL_ROUNDS) {
                        send(ChatEvent.ToolRoundStarting(round))
                    }
                    continue
                }

                // Execute tools in parallel
                val toolRequests = pendingToolCalls.map { tc ->
                    ToolCallRequest(
                        toolCallId = tc.id,
                        toolName = tc.name,
                        parameters = parseToolArguments(tc.arguments.toString())
                    )
                }
                val toolResponses = toolExecutionEngine.executeToolsParallel(
                    toolCalls = toolRequests,
                    availableToolNames = agent.toolIds
                )

                // Save tool call + result messages with final status
                for (tr in toolResponses) {
                    val isSuccess = tr.result.status == com.oneclaw.shadow.core.model.ToolResultStatus.SUCCESS
                    val finalStatus = if (isSuccess) ToolCallStatus.SUCCESS else ToolCallStatus.ERROR
                    messageRepository.addMessage(Message(
                        id = "", sessionId = sessionId, type = MessageType.TOOL_CALL,
                        content = "", thinkingContent = null,
                        toolCallId = tr.toolCallId, toolName = tr.toolName,
                        toolInput = pendingToolCalls.find { it.id == tr.toolCallId }?.arguments?.toString(),
                        toolOutput = null,
                        toolStatus = finalStatus, toolDurationMs = null,
                        tokenCountInput = null, tokenCountOutput = null,
                        modelId = null, providerId = null, createdAt = 0
                    ))
                    val rawOutput = tr.result.result ?: tr.result.errorMessage ?: ""
                    val truncatedOutput = ToolResultTruncator.truncate(rawOutput)
                    messageRepository.addMessage(Message(
                        id = "", sessionId = sessionId, type = MessageType.TOOL_RESULT,
                        content = "", thinkingContent = null,
                        toolCallId = tr.toolCallId, toolName = tr.toolName,
                        toolInput = null,
                        toolOutput = truncatedOutput,
                        toolStatus = finalStatus,
                        toolDurationMs = tr.durationMs,
                        tokenCountInput = null, tokenCountOutput = null,
                        modelId = null, providerId = null, createdAt = 0
                    ))
                    send(ChatEvent.ToolCallCompleted(tr.toolCallId, tr.toolName, tr.result))
                }

                // Drain pending user messages before starting next tool round
                val injectedBeforeTool = drainPendingMessages(pendingMessages)
                for (text in injectedBeforeTool) {
                    send(ChatEvent.UserMessageInjected(text))
                }
                round++
                if (round < MAX_TOOL_ROUNDS) {
                    send(ChatEvent.ToolRoundStarting(round))
                }
            }

            if (round >= MAX_TOOL_ROUNDS) {
                send(ChatEvent.Error(
                    "Reached maximum tool call rounds ($MAX_TOOL_ROUNDS). Stopping.",
                    ErrorCode.TOOL_ERROR, false
                ))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            val errorCode = mapApiError(e)
            val isRetryable = errorCode != ErrorCode.AUTH_ERROR
            send(ChatEvent.Error(e.message ?: "API request failed.", errorCode, isRetryable))
        } catch (e: Exception) {
            send(ChatEvent.Error(
                "An unexpected error occurred: ${e.message}",
                ErrorCode.UNKNOWN, true
            ))
        } finally {
            sessionRepository.setActive(sessionId, false)
        }
    }

    private suspend fun resolveModel(agent: Agent): Pair<AiModel, Provider>? {
        if (agent.preferredModelId != null && agent.preferredProviderId != null) {
            val provider = providerRepository.getProviderById(agent.preferredProviderId)
            if (provider != null && provider.isActive) {
                val models = providerRepository.getModelsForProvider(provider.id)
                val model = models.find { it.id == agent.preferredModelId }
                if (model != null) return Pair(model, provider)
            }
        }
        val defaultModel = providerRepository.getGlobalDefaultModel().first() ?: return null
        val provider = providerRepository.getProviderById(defaultModel.providerId) ?: return null
        if (!provider.isActive) return null
        return Pair(defaultModel, provider)
    }

    private fun mapApiError(e: ApiException): ErrorCode = when {
        e.code == "401" || e.code == "403" -> ErrorCode.AUTH_ERROR
        e.code == "429" -> ErrorCode.TIMEOUT_ERROR
        e.code?.startsWith("5") == true -> ErrorCode.PROVIDER_ERROR
        else -> ErrorCode.NETWORK_ERROR
    }

    private fun parseToolArguments(argumentsJson: String): Map<String, Any?> {
        if (argumentsJson.isBlank()) return emptyMap()
        return try {
            val jsonObj = json.parseToJsonElement(argumentsJson) as? JsonObject ?: return emptyMap()
            jsonObj.entries.associate { (k, v) ->
                k to when {
                    v is kotlinx.serialization.json.JsonPrimitive -> {
                        when {
                            v.isString -> v.content
                            v.content == "true" -> true
                            v.content == "false" -> false
                            v.content.toIntOrNull() != null -> v.content.toInt()
                            v.content.toLongOrNull() != null -> v.content.toLong()
                            v.content.toDoubleOrNull() != null -> v.content.toDouble()
                            else -> v.content
                        }
                    }
                    else -> v.toString()
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun drainPendingMessages(channel: Channel<String>): List<String> {
        val injected = mutableListOf<String>()
        while (true) {
            val text = channel.tryReceive().getOrNull() ?: break
            injected.add(text)
        }
        return injected
    }

    private data class PendingToolCall(
        val id: String,
        val name: String,
        val arguments: StringBuilder
    )
}

class ApiException(message: String, val code: String? = null) : Exception(message)
