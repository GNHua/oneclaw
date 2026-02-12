package com.tomandy.palmclaw.agent

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
 *
 * Phase 1: Simple request-response with tool call detection (no execution)
 * Phase 2: Full ReAct loop with tool execution
 *
 * @param clientProvider Function that provides the current LLM client (supports dynamic switching)
 * @param scope The coroutine scope for managing async operations
 */
class AgentCoordinator(
    private val clientProvider: () -> LlmClient,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {

    // Alternate constructor for backwards compatibility
    constructor(
        llmClient: LlmClient,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    ) : this(clientProvider = { llmClient }, scope = scope)

    private val _state = MutableStateFlow<AgentState>(AgentState.Idle)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private var currentJob: Job? = null

    private val conversationHistory = mutableListOf<Message>()

    private val toolExecutor = ToolExecutor()

    /**
     * Executes an AI agent request with the given user message.
     *
     * Phase 1 Behavior:
     * - Adds user message to conversation history
     * - Updates state to Thinking
     * - Calls LLM via ReActLoop
     * - If tool calls detected, returns their description
     * - Returns final response and updates state to Completed
     *
     * Phase 2 Behavior (future):
     * - Will execute tools when detected
     * - Will iterate through multiple ReAct cycles
     * - Will maintain full conversation context with tool results
     *
     * @param userMessage The user's input message
     * @param systemPrompt Optional system prompt to guide the AI's behavior
     * @param model The LLM model to use (default: gpt-4o-mini)
     * @return Result containing the final response or error
     */
    suspend fun execute(
        userMessage: String,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        model: String = ""
    ): Result<String> {
        // Cancel any existing job
        currentJob?.cancel()

        return try {
            // Update state to Thinking
            _state.value = AgentState.Thinking

            // Build messages list
            val messages = buildList {
                // Add system prompt if conversation is empty
                if (conversationHistory.isEmpty()) {
                    add(Message(role = "system", content = systemPrompt))
                }

                // Add conversation history
                addAll(conversationHistory)

                // Add user message
                add(Message(role = "user", content = userMessage))
            }

            // Store user message in history
            conversationHistory.add(Message(role = "user", content = userMessage))

            // Execute ReAct step with current client
            val reActLoop = ReActLoop(clientProvider())
            val result = reActLoop.step(
                messages = messages,
                model = model
            )

            result.fold(
                onSuccess = { response ->
                    // Add assistant response to history
                    conversationHistory.add(Message(role = "assistant", content = response))

                    // Update state to Completed
                    _state.value = AgentState.Completed(response)

                    Result.success(response)
                },
                onFailure = { error ->
                    // Update state to Error
                    _state.value = AgentState.Error(
                        message = error.message ?: "Unknown error",
                        exception = error
                    )

                    Result.failure(error)
                }
            )
        } catch (e: Exception) {
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
     * @param onComplete Optional callback invoked with the result
     */
    fun executeAsync(
        userMessage: String,
        systemPrompt: String = DEFAULT_SYSTEM_PROMPT,
        model: String = "gpt-4o-mini",
        onComplete: ((Result<String>) -> Unit)? = null
    ) {
        currentJob = scope.launch {
            val result = execute(
                userMessage = userMessage,
                systemPrompt = systemPrompt,
                model = model
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

    /**
     * Provides access to the tool executor for Phase 2 tool registration.
     *
     * @return The tool executor instance
     */
    fun getToolExecutor(): ToolExecutor {
        return toolExecutor
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
