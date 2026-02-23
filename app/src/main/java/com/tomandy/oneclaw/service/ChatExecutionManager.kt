package com.tomandy.oneclaw.service

import com.tomandy.oneclaw.agent.AgentCoordinator
import com.tomandy.oneclaw.data.dao.MessageDao
import com.tomandy.oneclaw.data.entity.MessageEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID

class ChatExecutionManager(private val messageDao: MessageDao) {

    private val lock = Any()
    private val activeCoordinators = mutableMapOf<String, AgentCoordinator>()
    private val activeJobs = mutableMapOf<String, Job>()

    fun registerCoordinator(conversationId: String, coordinator: AgentCoordinator) {
        synchronized(lock) { activeCoordinators[conversationId] = coordinator }
    }

    fun registerJob(conversationId: String, job: Job) {
        synchronized(lock) { activeJobs[conversationId] = job }
    }

    fun getCoordinator(conversationId: String): AgentCoordinator? {
        return synchronized(lock) { activeCoordinators[conversationId] }
    }

    fun isActiveJob(conversationId: String, job: Job): Boolean {
        return synchronized(lock) { activeJobs[conversationId] === job }
    }

    fun hasActiveJobs(): Boolean {
        return synchronized(lock) { activeJobs.isNotEmpty() }
    }

    fun getActiveConversationIds(): List<String> {
        return synchronized(lock) { activeCoordinators.keys.toList() }
    }

    /**
     * Removes the coordinator and job for [conversationId] only if [job] is still
     * the active job. Returns true if removal happened (caller should clean up).
     */
    fun removeIfActive(conversationId: String, job: Job): Boolean {
        synchronized(lock) {
            if (activeJobs[conversationId] !== job) return false
            val coordinator = activeCoordinators.remove(conversationId)
            coordinator?.cleanup()
            activeJobs.remove(conversationId)
            return true
        }
    }

    fun cancelExecution(conversationId: String) {
        ChatExecutionTracker.markInactive(conversationId)

        CoroutineScope(Dispatchers.IO).launch {
            messageDao.insert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    role = "meta",
                    content = "stopped",
                    toolName = "stopped",
                    timestamp = System.currentTimeMillis()
                )
            )
        }

        synchronized(lock) {
            val coordinator = activeCoordinators.remove(conversationId)
            coordinator?.cleanup()
            coordinator?.cancel()
            // Cancel the job but leave it in activeJobs -- the coroutine's finally
            // block uses the map entry to detect whether it's still the active
            // execution (vs. superseded by a newer one).
            activeJobs[conversationId]?.cancel()
        }
    }

    fun cancelAllExecutions() {
        val ids = synchronized(lock) { activeCoordinators.keys.toList() }
        ids.forEach { cancelExecution(it) }
    }
}
