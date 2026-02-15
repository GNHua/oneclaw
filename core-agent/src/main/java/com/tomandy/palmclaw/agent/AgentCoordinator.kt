package com.tomandy.palmclaw.agent

import android.util.Log
import com.tomandy.palmclaw.llm.LlmClient
import com.tomandy.palmclaw.llm.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Main orchestrator for the AI agent system.
 *
 * The AgentCoordinator is the brain of PalmClaw's AI agent, responsible for:
 * - Managing agent state (Idle, Thinking, Acting, etc.)
 * - Orchestrating the ReAct (Reasoning + Acting) loop
 * - Maintaining conversation history
 * - Handling cancellation and errors
 * - Coordinating tool execution via ToolRegistry
 *
 * Phase 2: Full ReAct loop with tool execution and multi-turn reasoning
 *
 * @param clientProvider Function that provides the current LLM client (supports dynamic switching)
 * @param toolRegistry Registry of available tools from loaded plugins
 * @param toolExecutor Executor for running tool calls
 * @param conversationId The conversation ID for persisting messages
 * @param scope The coroutine scope for managing async operations
 */
class AgentCoordinator(
    private val clientProvider: () -> LlmClient,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val messageStore: MessageStore,
    private val conversationId: String,
    private val contextWindow: Int = 200_000,
    private val summarizationThreshold: Float = 0.8f,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private var currentJob: Job? = null

    private val conversationHistory = mutableListOf<Message>()

    /** Tracks prompt tokens from the most recent LLM response. */
    private var lastPromptTokens: Int = 0

    /** Active summary of earlier conversation, if summarization has occurred. */
    private var conversationSummary: String? = null

    /** Last model used, so forceSummarize can reuse it. */
    private var lastModel: String = ""

    init {
        // Register summarization tool so the LLM can trigger summarization
        val plugin = SummarizationPlugin.createLoadedPlugin {
            forceSummarize()
        }
        toolRegistry.registerPlugin(plugin)
    }

    // ReActLoop with tool execution support
    private val reActLoop: ReActLoop by lazy {
        ReActLoop(
            llmClient = clientProvider(),
            toolExecutor = toolExecutor,
            messageStore = messageStore
        )
    }

    /**
     * Executes an AI agent request with the given user message.
     *
     * Phase 2 Behavior:
     * - Adds user message to conversation history
     * - Updates state to Thinking
     * - Calls LLM via ReActLoop with available tools
     * - Executes tools when the LLM requests them
     * - Iterates through multiple ReAct cycles until completion
     * - Returns final response and updates state to Completed
     *
     * @param userMessage The user's input message
     * @param systemPrompt Optional system prompt to guide the AI's behavior
     * @param model The LLM model to use (default: gpt-4o-mini)
     * @param context The execution context (interactive or scheduled)
     * @return Result containing the final response or error
     */
    suspend fun execute(
        userMessage: String,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        model: String = "",
        maxIterations: Int = 200,
        context: ExecutionContext = ExecutionContext.Interactive
    ): Result<String> {
        Log.d("AgentCoordinator", "execute called with message: $userMessage, model: $model")
        lastModel = model

        // Cancel any existing job
        currentJob?.cancel()

        return try {
            // Update state to Thinking
            Log.d("AgentCoordinator", "State changed to Thinking")
            _state.value = AgentState.Thinking

            // Check if we need to summarize before building messages
            val threshold = (contextWindow * summarizationThreshold).toInt()
            val estimatedTokens = if (lastPromptTokens > 0) {
                lastPromptTokens
            } else {
                conversationHistory.sumOf { (it.content?.length ?: 0) } / 4
            }

            if (estimatedTokens > threshold && conversationHistory.size > 2) {
                Log.d("AgentCoordinator", "Context at $estimatedTokens tokens (threshold: $threshold), summarizing")
                summarizeHistory(model)
            }

            // Build messages list
            val messages = buildList {
                // Build system prompt, including summary if available
                if (conversationHistory.isEmpty()) {
                    val basePrompt = when (context) {
                        is ExecutionContext.Interactive -> systemPrompt
                        is ExecutionContext.Scheduled ->
                            "$systemPrompt\n\nIMPORTANT: You are executing a scheduled task. " +
                            "Do not ask clarification questions. Use your best judgment and proceed autonomously. " +
                            "If you need to inform the user of something, include it in your response."
                    }
                    val fullPrompt = conversationSummary?.let {
                        "$basePrompt\n\n--- Earlier conversation summary ---\n$it\n--- End of summary ---"
                    } ?: basePrompt
                    add(Message(role = "system", content = fullPrompt))
                } else if (conversationSummary != null) {
                    // Mid-conversation: prepend summary as a system message
                    val basePrompt = when (context) {
                        is ExecutionContext.Interactive -> systemPrompt
                        is ExecutionContext.Scheduled ->
                            "$systemPrompt\n\nIMPORTANT: You are executing a scheduled task. " +
                            "Do not ask clarification questions. Use your best judgment and proceed autonomously. " +
                            "If you need to inform the user of something, include it in your response."
                    }
                    add(Message(
                        role = "system",
                        content = "$basePrompt\n\n--- Earlier conversation summary ---\n$conversationSummary\n--- End of summary ---"
                    ))
                }

                // Add conversation history
                addAll(conversationHistory)

                // Add user message (or task instruction in scheduled context)
                add(Message(role = "user", content = userMessage))
            }

            // Store user message in history
            conversationHistory.add(Message(role = "user", content = userMessage))

            // Get available tools from registry
            val tools = toolRegistry.getToolDefinitions()
            Log.d("AgentCoordinator", "Retrieved ${tools.size} tool definitions from registry")

            // Execute ReAct loop with tools
            Log.d("AgentCoordinator", "Calling reActLoop.step")
            val result = reActLoop.step(
                messages = messages,
                tools = tools,
                conversationId = conversationId,
                model = model,
                maxIterations = maxIterations
            )
            Log.d("AgentCoordinator", "reActLoop.step completed")

            // Update token tracking from usage
            reActLoop.lastUsage?.prompt_tokens?.let { lastPromptTokens = it }

            result.fold(
                onSuccess = { response ->
                    Log.d("AgentCoordinator", "ReAct loop success, response length: ${response.length}")
                    // Add assistant response to history
                    conversationHistory.add(Message(role = "assistant", content = response))

                    // Update state to Completed
                    _state.value = AgentState.Completed(response)

                    Result.success(response)
                },
                onFailure = { error ->
                    Log.e("AgentCoordinator", "ReAct loop failure: ${error.message}", error)
                    // Update state to Error
                    _state.value = AgentState.Error(
                        message = error.message ?: "Unknown error",
                        exception = error
                    )

                    Result.failure(error)
                }
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Don't swallow cancellation -- rethrow so callers can handle it
            throw e
        } catch (e: Exception) {
            Log.e("AgentCoordinator", "Unexpected error in execute: ${e.message}", e)
            // Handle unexpected errors
            _state.value = AgentState.Error(
                message = e.message ?: "Unexpected error",
                exception = e
            )
            Result.failure(e)
        }
    }

    /**
     * Summarizes older conversation history to free up context window space.
     *
     * Splits history into old messages (to summarize) and recent messages (to keep).
     * Calls the LLM to produce a summary, then replaces old messages with the summary.
     * Persists the summary as a meta message in the database.
     */
    private suspend fun summarizeHistory(model: String) {
        if (conversationHistory.size <= 2) return

        // Keep recent messages that fit in ~30% of context window
        val recentBudget = contextWindow * 0.3
        var recentChars = 0
        var splitIndex = conversationHistory.size
        for (i in conversationHistory.indices.reversed()) {
            val msgChars = conversationHistory[i].content?.length ?: 0
            if (recentChars + msgChars > recentBudget * 4) break // chars, not tokens
            recentChars += msgChars
            splitIndex = i
        }
        // Keep at least the last 2 messages
        splitIndex = splitIndex.coerceAtMost(conversationHistory.size - 2)
        if (splitIndex <= 0) return

        val oldMessages = conversationHistory.subList(0, splitIndex).toList()
        val recentMessages = conversationHistory.subList(splitIndex, conversationHistory.size).toList()

        // Format old messages for summarization
        val formatted = oldMessages.joinToString("\n") { msg ->
            val label = when (msg.role) {
                "user" -> "User"
                "assistant" -> "Assistant"
                else -> msg.role.replaceFirstChar { it.uppercase() }
            }
            "$label: ${msg.content ?: ""}"
        }

        val summarizationPrompt = "Summarize the following conversation concisely, " +
            "preserving key topics, decisions, user preferences, and any pending tasks.\n\n$formatted"

        try {
            val summaryResult = clientProvider().complete(
                messages = listOf(Message(role = "user", content = summarizationPrompt)),
                model = model
            )

            val summaryText = summaryResult.getOrNull()
                ?.choices?.firstOrNull()?.message?.content

            if (!summaryText.isNullOrBlank()) {
                conversationSummary = summaryText
                conversationHistory.clear()
                conversationHistory.addAll(recentMessages)
                lastPromptTokens = 0 // Reset -- next call will get fresh usage

                // Persist summary as a meta message
                messageStore.insert(
                    MessageRecord(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = "meta",
                        content = summaryText,
                        toolName = "summary",
                        timestamp = System.currentTimeMillis()
                    )
                )

                Log.d("AgentCoordinator", "Summarized ${oldMessages.size} messages, keeping ${recentMessages.size}")
            }
        } catch (e: Exception) {
            Log.w("AgentCoordinator", "Summarization failed, continuing without: ${e.message}")
        }
    }

    /**
     * Force-summarize the conversation, regardless of token threshold.
     * Used by the /summarize command and the summarize_conversation tool.
     *
     * @return Status message describing the result.
     */
    suspend fun forceSummarize(model: String? = null): String {
        if (conversationHistory.size <= 2) {
            return "Not enough conversation history to summarize."
        }
        forceSummarizeHistory(model ?: lastModel)
        return if (conversationSummary != null) {
            "Conversation summarized successfully."
        } else {
            "Summarization failed -- conversation unchanged."
        }
    }

    /**
     * Force-summarize: summarizes all but the last 2 messages,
     * regardless of the recent-budget heuristic used by [summarizeHistory].
     */
    private suspend fun forceSummarizeHistory(model: String) {
        if (conversationHistory.size <= 2) return

        val splitIndex = conversationHistory.size - 2
        val oldMessages = conversationHistory.subList(0, splitIndex).toList()
        val recentMessages = conversationHistory.subList(splitIndex, conversationHistory.size).toList()

        val formatted = oldMessages.joinToString("\n") { msg ->
            val label = when (msg.role) {
                "user" -> "User"
                "assistant" -> "Assistant"
                else -> msg.role.replaceFirstChar { it.uppercase() }
            }
            "$label: ${msg.content ?: ""}"
        }

        val summarizationPrompt = "Summarize the following conversation concisely, " +
            "preserving key topics, decisions, user preferences, and any pending tasks.\n\n$formatted"

        try {
            val summaryResult = clientProvider().complete(
                messages = listOf(Message(role = "user", content = summarizationPrompt)),
                model = model
            )

            val summaryText = summaryResult.getOrNull()
                ?.choices?.firstOrNull()?.message?.content

            if (!summaryText.isNullOrBlank()) {
                conversationSummary = summaryText
                conversationHistory.clear()
                conversationHistory.addAll(recentMessages)
                lastPromptTokens = 0

                messageStore.insert(
                    MessageRecord(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = "meta",
                        content = summaryText,
                        toolName = "summary",
                        timestamp = System.currentTimeMillis()
                    )
                )

                Log.d("AgentCoordinator", "Force-summarized ${oldMessages.size} messages, keeping ${recentMessages.size}")
            }
        } catch (e: Exception) {
            Log.w("AgentCoordinator", "Force-summarization failed: ${e.message}")
        }
    }

    /**
     * Unregister the summarization tool from the shared registry.
     * Must be called when this coordinator is no longer active.
     */
    fun cleanup() {
        toolRegistry.unregisterPlugin(SummarizationPlugin.PLUGIN_ID)
    }

    /**
     * Executes an agent request asynchronously in the coordinator's scope.
     *
     * This method launches the execution in the background and returns immediately.
     * State updates can be observed via the [state] StateFlow.
     *
     * @param userMessage The user's input message
     * @param systemPrompt Optional system prompt
     * @param model The LLM model to use
     * @param context The execution context (interactive or scheduled)
     * @param onComplete Optional callback invoked with the result
     */
    fun executeAsync(
        userMessage: String,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        model: String = "gpt-4o-mini",
        context: ExecutionContext = ExecutionContext.Interactive,
        onComplete: ((Result<String>) -> Unit)? = null
    ) {
        currentJob = scope.launch {
            val result = execute(
                userMessage = userMessage,
                systemPrompt = systemPrompt,
                model = model,
                context = context
            )
            onComplete?.invoke(result)
        }
    }

    /**
     * Injects a user message into the currently running ReAct loop.
     * The message will be picked up on the next iteration.
     */
    fun injectMessage(text: String) {
        reActLoop.injectMessage(text)
    }

    /**
     * Cancels the current agent execution.
     *
     * This will stop any in-progress LLM calls or tool executions
     * and reset the state to Idle.
     */
    fun cancel() {
        clientProvider().cancel()
        currentJob?.cancel()
        currentJob = null
        _state.value = AgentState.Idle
    }

    /**
     * Resets the agent coordinator.
     *
     * Clears the conversation history and resets state to Idle.
     * Cancels any in-progress execution.
     */
    fun reset() {
        currentJob?.cancel()
        currentJob = null
        conversationHistory.clear()
        _state.value = AgentState.Idle
    }

    /**
     * Seeds conversation history from persisted messages.
     * Used when loading an existing conversation from the database.
     *
     * @param messages The conversation messages to seed
     * @param summary Optional summary from a previous summarization, to be prepended to the system prompt
     */
    fun seedHistory(messages: List<Message>, summary: String? = null) {
        conversationHistory.clear()
        conversationHistory.addAll(messages)
        conversationSummary = summary
    }

    /**
     * Returns the current conversation history.
     *
     * @return Immutable copy of the conversation history
     */
    fun getConversationHistory(): List<Message> {
        return conversationHistory.toList()
    }

    /**
     * Returns the number of messages in the conversation history.
     */
    fun getConversationSize(): Int {
        return conversationHistory.size
    }

    companion object {
        /**
         * Default system prompt for the AI agent.
         */
        const val DEFAULT_SYSTEM_PROMPT =
            "You are a helpful AI assistant. Be concise and accurate."

        /**
         * Alternative system prompt for tool-aware agents (Phase 2).
         */
        const val TOOL_AWARE_SYSTEM_PROMPT =
            "You are a helpful AI assistant with access to tools. " +
            "When you need information or need to perform actions, use the available tools. " +
            "Be concise and accurate in your responses."
    }
}
