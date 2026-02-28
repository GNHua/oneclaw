package com.oneclaw.shadow.feature.memory.trigger

import android.util.Log
import com.oneclaw.shadow.core.repository.SessionRepository
import com.oneclaw.shadow.feature.memory.MemoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates memory trigger events.
 * Ensures daily log flushes are not duplicated via a Mutex.
 */
class MemoryTriggerManager(
    private val memoryManager: MemoryManager,
    private val sessionRepository: SessionRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    companion object {
        private const val TAG = "MemoryTriggerManager"
    }

    /**
     * Called when the app goes to the background.
     * Registered via ProcessLifecycleOwner in Application class.
     */
    fun onAppBackground() {
        scope.launch {
            flushActiveSession()
        }
    }

    /**
     * Called when the user switches from one session to another.
     */
    fun onSessionSwitch(previousSessionId: String) {
        scope.launch {
            flushSession(previousSessionId)
        }
    }

    /**
     * Called when the date changes during an active session.
     * Detected by a periodic check or system broadcast.
     */
    fun onDayChange(activeSessionId: String) {
        scope.launch {
            flushSession(activeSessionId)
        }
    }

    /**
     * Called before FEAT-011 Auto Compact compresses message history.
     * This is the pre-compaction flush integration point.
     */
    suspend fun onPreCompaction(sessionId: String) {
        flushSession(sessionId)
    }

    /**
     * Called when a session ends (user explicitly closes it).
     */
    fun onSessionEnd(sessionId: String) {
        scope.launch {
            flushSession(sessionId)
        }
    }

    private suspend fun flushSession(sessionId: String) {
        // Use mutex to prevent concurrent flush for the same session
        mutex.withLock {
            val result = memoryManager.flushDailyLog(sessionId)
            result.onFailure { e ->
                Log.w(TAG, "Daily log flush failed for session $sessionId: ${e.message}")
            }
        }
    }

    private suspend fun flushActiveSession() {
        val activeSession = sessionRepository.getActiveSession()
        if (activeSession != null) {
            flushSession(activeSession.id)
        }
    }
}
