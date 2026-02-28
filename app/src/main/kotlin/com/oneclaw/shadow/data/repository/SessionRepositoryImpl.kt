package com.oneclaw.shadow.data.repository

import com.oneclaw.shadow.core.model.Session
import com.oneclaw.shadow.core.repository.SessionRepository
import com.oneclaw.shadow.core.util.DateTimeUtils
import com.oneclaw.shadow.data.local.dao.SessionDao
import com.oneclaw.shadow.data.local.mapper.toDomain
import com.oneclaw.shadow.data.local.mapper.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class SessionRepositoryImpl(
    private val sessionDao: SessionDao
) : SessionRepository {

    override fun getAllSessions(): Flow<List<Session>> =
        sessionDao.getAllSessions().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getSessionById(id: String): Session? =
        sessionDao.getSessionById(id)?.toDomain()

    override suspend fun createSession(session: Session): Session {
        val now = DateTimeUtils.now()
        val withId = if (session.id.isBlank()) session.copy(
            id = UUID.randomUUID().toString(),
            createdAt = if (session.createdAt == 0L) now else session.createdAt,
            updatedAt = if (session.updatedAt == 0L) now else session.updatedAt
        ) else session
        sessionDao.insert(withId.toEntity())
        return withId
    }

    override suspend fun updateSession(session: Session) {
        sessionDao.update(session.toEntity())
    }

    override suspend fun softDeleteSession(id: String) {
        sessionDao.softDelete(id, DateTimeUtils.now())
    }

    override suspend fun softDeleteSessions(ids: List<String>) {
        sessionDao.softDeleteBatch(ids, DateTimeUtils.now())
    }

    override suspend fun restoreSession(id: String) {
        sessionDao.restore(id)
    }

    override suspend fun restoreSessions(ids: List<String>) {
        sessionDao.restoreBatch(ids)
    }

    override suspend fun hardDeleteSession(id: String) {
        sessionDao.hardDelete(id)
    }

    override suspend fun hardDeleteAllSoftDeleted() {
        sessionDao.hardDeleteAllSoftDeleted()
    }

    override suspend fun updateAgentForSessions(oldAgentId: String, newAgentId: String) {
        sessionDao.updateAgentForSessions(oldAgentId, newAgentId)
    }

    override suspend fun updateTitle(id: String, title: String) {
        sessionDao.updateTitle(id, title, DateTimeUtils.now())
    }

    override suspend fun setGeneratedTitle(id: String, title: String) {
        sessionDao.updateTitle(id, title, DateTimeUtils.now())
    }

    override suspend fun updateMessageStats(id: String, count: Int, preview: String?) {
        sessionDao.updateMessageStats(id, count, preview, DateTimeUtils.now())
    }

    override suspend fun setActive(id: String, isActive: Boolean) {
        sessionDao.setActive(id, isActive)
    }

    override suspend fun updateCurrentAgent(id: String, agentId: String) {
        sessionDao.updateCurrentAgent(id, agentId, DateTimeUtils.now())
    }
}
