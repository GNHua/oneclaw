package com.tomandy.palmclaw.scheduler

/**
 * Interface for executing agent tasks from scheduled jobs.
 *
 * This interface allows the scheduler library to execute agent tasks
 * without directly depending on the app's agent implementation.
 *
 * The app should provide an implementation of this interface and
 * register it globally so workers can access it.
 */
interface AgentExecutor {
    /**
     * Execute an agent task with the given instruction.
     *
     * @param instruction The task instruction in natural language
     * @param cronjobId The ID of the cronjob being executed
     * @param triggerTime When the task was triggered (Unix timestamp in millis)
     * @return A summary of what the agent did
     */
    suspend fun executeTask(
        instruction: String,
        cronjobId: String,
        triggerTime: Long
    ): Result<String>

    companion object {
        /**
         * Global instance of AgentExecutor.
         *
         * The app should set this during application initialization.
         * Workers will access this to execute tasks.
         */
        @Volatile
        var instance: AgentExecutor? = null
    }
}
