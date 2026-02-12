package com.tomandy.palmclaw.agent

/**
 * Represents the context in which the agent is executing.
 *
 * This allows the agent to adapt its behavior based on whether it's running
 * interactively (user chat) or autonomously (scheduled task).
 */
sealed class ExecutionContext {
    /**
     * Interactive execution - normal chat with the user.
     *
     * In this mode:
     * - The agent can ask clarification questions
     * - Responses are displayed in the chat UI
     * - User is present and can provide feedback
     */
    object Interactive : ExecutionContext()

    /**
     * Scheduled execution - running as a cronjob.
     *
     * In this mode:
     * - The agent should not ask clarification questions
     * - Should use best judgment when ambiguous
     * - May send notifications instead of chat messages
     * - User is not actively monitoring
     *
     * @param cronjobId The ID of the cronjob being executed
     * @param triggerTime When the cronjob was triggered (Unix timestamp in millis)
     */
    data class Scheduled(
        val cronjobId: String,
        val triggerTime: Long
    ) : ExecutionContext()

    /**
     * Check if this is interactive execution
     */
    val isInteractive: Boolean
        get() = this is Interactive

    /**
     * Check if this is scheduled execution
     */
    val isScheduled: Boolean
        get() = this is Scheduled
}
