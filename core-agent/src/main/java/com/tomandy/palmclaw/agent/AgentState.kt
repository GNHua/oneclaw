package com.tomandy.palmclaw.agent

/**
 * Represents the various states of the AI agent during execution.
 * Used for reactive state management and UI updates.
 */
sealed class AgentState {
    /**
     * Agent is not currently processing any requests.
     */
    object Idle : AgentState()

    /**
     * Agent is reasoning about the user's request or processing LLM responses.
     */
    object Thinking : AgentState()

    /**
     * Agent is executing a tool with the given parameters.
     * @param toolName The name of the tool being executed
     * @param arguments The JSON string of tool arguments
     */
    data class Acting(val toolName: String, val arguments: String) : AgentState()

    /**
     * Agent is processing the result of a tool execution.
     * @param observation The observation/result from the tool execution
     */
    data class Observing(val observation: String) : AgentState()

    /**
     * Agent has completed processing and produced a final response.
     * @param response The final response to return to the user
     */
    data class Completed(val response: String) : AgentState()

    /**
     * Agent encountered an error during execution.
     * @param message Human-readable error message
     * @param exception Optional exception that caused the error
     */
    data class Error(val message: String, val exception: Throwable? = null) : AgentState()
}
