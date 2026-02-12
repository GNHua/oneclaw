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
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private var currentJob: Job? = null

    private val conversationHistory = mutableListOf<Message>()

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
        context: ExecutionContext = ExecutionContext.Interactive
    ): Result<String> {
        Log.d("AgentCoordinator", "execute called with message: $userMessage, model: $model")

        // Cancel any existing job
        currentJob?.cancel()

        return try {
            // Update state to Thinking
            Log.d("AgentCoordinator", "State changed to Thinking")
            _state.value = AgentState.Thinking

            // Build messages list
            val messages = buildList {
                // Add system prompt if conversation is empty
                if (conversationHistory.isEmpty()) {
                    val contextualPrompt = when (context) {
                        is ExecutionContext.Interactive -> systemPrompt
                        is ExecutionContext.Scheduled ->
                            "$systemPrompt\n\nIMPORTANT: You are executing a scheduled task. " +
                            "Do not ask clarification questions. Use your best judgment and proceed autonomously. " +
                            "If you need to inform the user of something, include it in your response."
                    }
                    add(Message(role = "system", content = contextualPrompt))
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
                model = model
            )
            Log.d("AgentCoordinator", "reActLoop.step completed")

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
     * Cancels the current agent execution.
     *
     * This will stop any in-progress LLM calls or tool executions
     * and reset the state to Idle.
     */
    fun cancel() {
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
            "You are a helpful AI assistant for Android. Be concise and accurate."

        /**
         * Alternative system prompt for tool-aware agents (Phase 2).
         */
        const val TOOL_AWARE_SYSTEM_PROMPT =
            "You are a helpful AI assistant for Android with access to tools. " +
            "When you need information or need to perform actions, use the available tools. " +
            "Be concise and accurate in your responses."
    }
}
