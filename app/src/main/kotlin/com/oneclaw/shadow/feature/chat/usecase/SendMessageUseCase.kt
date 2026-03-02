package com.oneclaw.shadow.feature.chat.usecase

import com.oneclaw.shadow.core.model.AiModel
import com.oneclaw.shadow.core.model.Agent
import com.oneclaw.shadow.core.model.Attachment
import com.oneclaw.shadow.core.model.Citation
import com.oneclaw.shadow.core.model.Message
import com.oneclaw.shadow.core.model.MessageType
import com.oneclaw.shadow.core.model.Provider
import com.oneclaw.shadow.core.model.ProviderCapability
import com.oneclaw.shadow.core.model.ToolCallStatus
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.core.model.ToolResultStatus
import com.oneclaw.shadow.core.repository.AgentRepository
import com.oneclaw.shadow.core.repository.AttachmentRepository
import com.oneclaw.shadow.core.repository.MessageRepository
import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.core.repository.SessionRepository
import com.oneclaw.shadow.core.util.ErrorCode
import com.oneclaw.shadow.core.util.ToolResultTruncator
import com.oneclaw.shadow.data.local.AttachmentFileManager
import com.oneclaw.shadow.data.remote.adapter.ApiAttachment
import com.oneclaw.shadow.data.remote.adapter.ApiMessage
import com.oneclaw.shadow.data.remote.adapter.ModelApiAdapterFactory
import com.oneclaw.shadow.feature.memory.injection.MemoryInjector
import com.oneclaw.shadow.data.remote.adapter.StreamEvent
import com.oneclaw.shadow.data.security.ApiKeyStorage
import com.oneclaw.shadow.feature.chat.ChatEvent
import com.oneclaw.shadow.tool.engine.ToolCallRequest
import com.oneclaw.shadow.tool.engine.ToolExecutionEngine
import com.oneclaw.shadow.tool.engine.ToolRegistry
import com.oneclaw.shadow.tool.skill.SkillRegistry
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
    private val memoryInjector: MemoryInjector? = null,
    private val skillRegistry: SkillRegistry? = null,
    private val attachmentFileManager: AttachmentFileManager? = null,
    private val attachmentRepository: AttachmentRepository? = null
) {
    companion object {
        const val MAX_TOOL_ROUNDS = 100
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun execute(
        sessionId: String,
        userText: String,
        agentId: String,
        pendingMessages: Channel<String> = Channel(Channel.UNLIMITED),
        pendingAttachments: List<AttachmentFileManager.PendingAttachment> = emptyList()
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
        val savedUserMessage = messageRepository.addMessage(userMessage)

        // 3b. Save attachments linked to this user message
        if (pendingAttachments.isNotEmpty() && attachmentRepository != null) {
            val now = System.currentTimeMillis()
            val attachmentDomains = pendingAttachments.map { pending ->
                Attachment(
                    id = pending.id,
                    messageId = savedUserMessage.id,
                    type = pending.type,
                    fileName = pending.fileName,
                    mimeType = pending.mimeType,
                    fileSize = pending.fileSize,
                    filePath = pending.filePath,
                    thumbnailPath = pending.thumbnailPath,
                    width = pending.width,
                    height = pending.height,
                    durationMs = pending.durationMs,
                    createdAt = now
                )
            }
            attachmentRepository.addAttachments(attachmentDomains)
        }

        // 4. Update session stats
        sessionRepository.updateMessageStats(
            id = sessionId,
            count = messageRepository.getMessageCount(sessionId),
            preview = userText.take(100)
        )
        sessionRepository.setActive(sessionId, true)

        // 5. Build dynamic tool list: start with core tools only
        val loadedGroupNames = mutableSetOf<String>()
        val activeToolDefs = toolRegistry.getCoreToolDefinitions().toMutableList()

        // Determine effective max iterations from agent or global default
        val effectiveMaxRounds = agent.maxIterations ?: Int.MAX_VALUE

        var round = 0
        try {
            // Inject memory context into system prompt (only on first round)
            val memorySystemPrompt = if (memoryInjector != null) {
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

            // RFC-014: Inject skill registry into system prompt
            // RFC-040: Inject tool group listing after skills
            val baseSystemPrompt = buildSystemPromptWithToolGroups(
                buildSystemPromptWithSkills(memorySystemPrompt)
            )

            // Build api attachments for first round only
            val apiAttachments: List<ApiAttachment> = if (pendingAttachments.isNotEmpty() && attachmentFileManager != null) {
                pendingAttachments
                    .filter { ProviderCapability.supportsAttachmentType(provider.type, it.type) }
                    .mapNotNull { pending ->
                        try {
                            ApiAttachment(
                                type = pending.type,
                                mimeType = pending.mimeType,
                                base64Data = attachmentFileManager.readAsBase64(pending.filePath),
                                fileName = pending.fileName
                            )
                        } catch (e: Exception) {
                            null
                        }
                    }
            } else emptyList()

            while (round < effectiveMaxRounds) {
                val allMessages = messageRepository.getMessagesSnapshot(sessionId)
                val session = sessionRepository.getSessionById(sessionId)!!
                val (effectiveSystemPrompt, apiMessages) = CompactAwareMessageBuilder.build(
                    session = session,
                    allMessages = allMessages,
                    originalSystemPrompt = baseSystemPrompt
                )

                // Inject attachments into the last user message (first round only)
                val messagesWithAttachments = if (round == 0 && apiAttachments.isNotEmpty()) {
                    val lastUserIndex = apiMessages.indexOfLast { it is ApiMessage.User }
                    if (lastUserIndex >= 0) {
                        apiMessages.toMutableList().also { list ->
                            val userMsg = list[lastUserIndex] as ApiMessage.User
                            list[lastUserIndex] = userMsg.copy(attachments = apiAttachments)
                        }
                    } else apiMessages
                } else apiMessages

                val adapter = adapterFactory.getAdapter(provider.type)

                var accumulatedText = ""
                var accumulatedThinking = ""
                val pendingToolCalls = mutableListOf<PendingToolCall>()
                var usage: ChatEvent.TokenUsage? = null
                val accumulatedCitations = mutableListOf<Citation>()

                adapter.sendMessageStream(
                    apiBaseUrl = provider.apiBaseUrl,
                    apiKey = apiKey,
                    modelId = model.id,
                    messages = messagesWithAttachments,
                    tools = activeToolDefs.takeIf { it.isNotEmpty() },
                    systemPrompt = effectiveSystemPrompt,
                    webSearchEnabled = agent.webSearchEnabled,
                    temperature = agent.temperature
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
                        is StreamEvent.WebSearchStart -> {
                            send(ChatEvent.WebSearchStarted(event.query))
                        }
                        is StreamEvent.Citations -> {
                            accumulatedCitations.addAll(event.citations)
                        }
                    }
                }

                val finalCitations = accumulatedCitations.distinctBy { it.url }.ifEmpty { null }

                // Save AI response
                val aiMessage = messageRepository.addMessage(Message(
                    id = "", sessionId = sessionId, type = MessageType.AI_RESPONSE,
                    content = accumulatedText,
                    thinkingContent = accumulatedThinking.ifEmpty { null },
                    toolCallId = null, toolName = null, toolInput = null, toolOutput = null,
                    toolStatus = null, toolDurationMs = null,
                    tokenCountInput = usage?.inputTokens, tokenCountOutput = usage?.outputTokens,
                    modelId = model.id, providerId = provider.id, createdAt = 0,
                    citations = finalCitations
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
                    if (round < effectiveMaxRounds) {
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
                    availableToolNames = activeToolDefs.map { it.name }
                )

                // RFC-040: Expand active tool list when load_tool_group succeeds
                for (tr in toolResponses) {
                    if (tr.toolName == "load_tool_group" &&
                        tr.result.status == ToolResultStatus.SUCCESS
                    ) {
                        val groupName = extractGroupName(toolRequests, tr.toolCallId)
                        if (groupName != null && loadedGroupNames.add(groupName)) {
                            val groupDefs = toolRegistry.getGroupToolDefinitions(groupName)
                            activeToolDefs.addAll(groupDefs)
                        }
                    }
                }

                // Save all TOOL_CALL messages first, then all TOOL_RESULT messages.
                // This ensures MessageToApiMapper can collect consecutive TOOL_CALL rows
                // after an AI_RESPONSE before encountering any TOOL_RESULT rows.
                for (tr in toolResponses) {
                    val isSuccess = tr.result.status == ToolResultStatus.SUCCESS
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
                }
                for (tr in toolResponses) {
                    val isSuccess = tr.result.status == ToolResultStatus.SUCCESS
                    val finalStatus = if (isSuccess) ToolCallStatus.SUCCESS else ToolCallStatus.ERROR
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
                if (round < effectiveMaxRounds) {
                    send(ChatEvent.ToolRoundStarting(round))
                }
            }

            if (round >= effectiveMaxRounds) {
                send(ChatEvent.Error(
                    "Reached maximum tool call rounds ($effectiveMaxRounds). Stopping.",
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

    /**
     * RFC-040: Append tool group listing to system prompt when groups are available.
     */
    private fun buildSystemPromptWithToolGroups(basePrompt: String): String {
        val groups = toolRegistry.getAllGroupDefinitions()
        if (groups.isEmpty()) return basePrompt

        val registryPrompt = buildString {
            appendLine("## Available Tool Groups")
            appendLine()
            appendLine("Use `load_tool_group` to load tools from a group before using them.")
            appendLine()
            groups.forEach { group ->
                appendLine("- ${group.name}: ${group.description}")
            }
        }.trimEnd()

        return if (basePrompt.isBlank()) registryPrompt
        else "$basePrompt\n\n---\n\n$registryPrompt"
    }

    private fun extractGroupName(
        toolRequests: List<ToolCallRequest>,
        toolCallId: String
    ): String? {
        val request = toolRequests.find { it.toolCallId == toolCallId } ?: return null
        return request.parameters["group_name"] as? String
    }

    /**
     * RFC-014: Append skill registry to system prompt when skills are available.
     */
    private fun buildSystemPromptWithSkills(basePrompt: String): String {
        val registry = skillRegistry ?: return basePrompt
        val registryPrompt = registry.generateRegistryPrompt()
        return if (registryPrompt.isBlank()) {
            basePrompt
        } else if (basePrompt.isBlank()) {
            registryPrompt
        } else {
            "$basePrompt\n\n---\n\n$registryPrompt"
        }
    }

    private data class PendingToolCall(
        val id: String,
        val name: String,
        val arguments: StringBuilder
    )
}

class ApiException(message: String, val code: String? = null) : Exception(message)
