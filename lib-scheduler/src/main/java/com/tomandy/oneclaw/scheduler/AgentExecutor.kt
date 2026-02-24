package com.tomandy.oneclaw.scheduler

/**
 * Result of a scheduled task execution.
 */
data class TaskExecutionResult(
    val summary: String,
    val conversationId: String? = null
)

/**
 * Interface for executing agent tasks from scheduled jobs.
 *
 * This interface allows the scheduler library to execute agent tasks
 * without directly depending on the app's agent implementation.
 *
 * The app provides an implementation via Koin dependency injection.
 */
interface AgentExecutor {
    /**
     * Execute an agent task with the given instruction.
     *
     * @param instruction The task instruction in natural language
     * @param cronjobId The ID of the cronjob being executed
     * @param triggerTime When the task was triggered (Unix timestamp in millis)
     * @param conversationId The original conversation to post results to (null = no posting)
     * @param agentName Optional agent profile name to use (null = use global active agent)
     * @return A [TaskExecutionResult] containing summary and conversation ID
     */
    suspend fun executeTask(
        instruction: String,
        cronjobId: String,
        triggerTime: Long,
        conversationId: String? = null,
        agentName: String? = null
    ): Result<TaskExecutionResult>
}
