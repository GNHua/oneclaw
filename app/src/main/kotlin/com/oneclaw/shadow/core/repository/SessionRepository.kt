package com.oneclaw.shadow.core.repository

import com.oneclaw.shadow.core.model.Session
import kotlinx.coroutines.flow.Flow

interface SessionRepository {
    fun getAllSessions(): Flow<List<Session>>
    suspend fun getSessionById(id: String): Session?
    suspend fun createSession(session: Session): Session
    suspend fun updateSession(session: Session)
    suspend fun softDeleteSession(id: String)
    suspend fun softDeleteSessions(ids: List<String>)
    suspend fun restoreSession(id: String)
    suspend fun restoreSessions(ids: List<String>)
    suspend fun hardDeleteSession(id: String)
    suspend fun hardDeleteAllSoftDeleted()
    suspend fun updateAgentForSessions(oldAgentId: String, newAgentId: String)
    suspend fun updateTitle(id: String, title: String)
    suspend fun setGeneratedTitle(id: String, title: String)
    suspend fun updateMessageStats(id: String, count: Int, preview: String?)
    suspend fun setActive(id: String, isActive: Boolean)
    suspend fun updateCurrentAgent(id: String, agentId: String)
    suspend fun updateCompactedSummary(id: String, summary: String?, boundaryTimestamp: Long?)
    suspend fun updateLastLoggedMessageId(sessionId: String, messageId: String)
    suspend fun getActiveSession(): Session?
}
