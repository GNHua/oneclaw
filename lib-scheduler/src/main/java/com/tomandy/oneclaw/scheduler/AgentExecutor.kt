package com.tomandy.oneclaw.scheduler

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
     * @return A summary of what the agent did
     */
    suspend fun executeTask(
        instruction: String,
        cronjobId: String,
        triggerTime: Long,
        conversationId: String? = null
    ): Result<String>
}
