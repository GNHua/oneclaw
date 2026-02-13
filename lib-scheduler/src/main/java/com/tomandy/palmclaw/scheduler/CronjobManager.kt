package com.tomandy.palmclaw.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.*
import com.tomandy.palmclaw.scheduler.data.*
import com.tomandy.palmclaw.scheduler.receiver.CronjobAlarmReceiver
import com.tomandy.palmclaw.scheduler.worker.AgentTaskWorker
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Main API for scheduling and managing cronjobs
 *
 * This manager coordinates between WorkManager (for recurring tasks)
 * and AlarmManager (for exact-time tasks) to provide a unified scheduling interface.
 */
class CronjobManager(private val context: Context) {

    private val database = CronjobDatabase.getDatabase(context)
    private val cronjobDao = database.cronjobDao()
    private val executionLogDao = database.executionLogDao()
    private val workManager = WorkManager.getInstance(context)
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Schedule a new cronjob
     *
     * @param cronjob The cronjob configuration
     * @return The ID of the scheduled cronjob
     */
    suspend fun schedule(cronjob: CronjobEntity): String {
        // Validate the cronjob
        validate(cronjob)

        // Insert into database
        cronjobDao.insert(cronjob)

        // Schedule based on type
        when (cronjob.scheduleType) {
            ScheduleType.ONE_TIME -> scheduleOneTime(cronjob)
            ScheduleType.RECURRING -> scheduleRecurring(cronjob)
            ScheduleType.CONDITIONAL -> {
                // Future enhancement - for now, treat as recurring with short interval
                scheduleRecurring(cronjob.copy(intervalMinutes = 15))
            }
        }

        return cronjob.id
    }

    /**
     * Cancel a scheduled cronjob
     */
    suspend fun cancel(cronjobId: String) {
        val cronjob = cronjobDao.getById(cronjobId) ?: return

        // Cancel WorkManager job if exists
        cronjob.workManagerId?.let { workId ->
            workManager.cancelWorkById(UUID.fromString(workId))
        }

        // Cancel AlarmManager alarm
        cancelAlarm(cronjobId)

        // Disable the cronjob
        cronjobDao.updateEnabled(cronjobId, false)
    }

    /**
     * Enable or disable a cronjob
     */
    suspend fun setEnabled(cronjobId: String, enabled: Boolean) {
        if (enabled) {
            cronjobDao.updateEnabled(cronjobId, true)
        } else {
            cancel(cronjobId)
        }
    }

    /**
     * Delete a cronjob completely
     */
    suspend fun delete(cronjobId: String) {
        cancel(cronjobId)
        cronjobDao.deleteById(cronjobId)
        executionLogDao.deleteLogsForCronjob(cronjobId)
    }

    /**
     * Get a cronjob by ID
     */
    suspend fun getById(id: String): CronjobEntity? {
        return cronjobDao.getById(id)
    }

    /**
     * Get all enabled cronjobs
     */
    fun getAllEnabled(): Flow<List<CronjobEntity>> {
        return cronjobDao.getAllEnabled()
    }

    /**
     * Get all cronjobs (enabled and disabled)
     */
    fun getAll(): Flow<List<CronjobEntity>> {
        return cronjobDao.getAll()
    }

    /**
     * Get execution logs for a specific cronjob
     */
    fun getExecutionLogs(cronjobId: String): Flow<List<ExecutionLog>> {
        return executionLogDao.getLogsForCronjob(cronjobId)
    }

    /**
     * Record the start of a cronjob execution
     */
    suspend fun recordExecutionStart(cronjobId: String): Long {
        val log = ExecutionLog(
            cronjobId = cronjobId,
            startedAt = System.currentTimeMillis(),
            status = ExecutionStatus.SUCCESS
        )
        return executionLogDao.insert(log)
    }

    /**
     * Record the completion of a cronjob execution
     */
    suspend fun recordExecutionComplete(
        logId: Long,
        status: ExecutionStatus,
        resultSummary: String? = null,
        errorMessage: String? = null
    ) {
        val log = executionLogDao.getById(logId) ?: return
        executionLogDao.update(
            log.copy(
                completedAt = System.currentTimeMillis(),
                status = status,
                resultSummary = resultSummary,
                errorMessage = errorMessage
            )
        )

        // Update cronjob's last execution time
        cronjobDao.updateLastExecution(log.cronjobId, System.currentTimeMillis())

        // Check if max executions reached
        val cronjob = cronjobDao.getById(log.cronjobId)
        if (cronjob != null && cronjob.maxExecutions != null) {
            if (cronjob.executionCount + 1 >= cronjob.maxExecutions) {
                // Auto-disable after reaching max executions
                cronjobDao.updateEnabled(cronjob.id, false)
                cancel(cronjob.id)
            }
        }
    }

    /**
     * Schedule a one-time task using AlarmManager
     */
    private fun scheduleOneTime(cronjob: CronjobEntity) {
        require(cronjob.executeAt != null) { "executeAt must be set for ONE_TIME cronjobs" }

        val intent = Intent(context, CronjobAlarmReceiver::class.java).apply {
            action = CronjobAlarmReceiver.ACTION_EXECUTE_CRONJOB
            putExtra(CronjobAlarmReceiver.EXTRA_CRONJOB_ID, cronjob.id)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            cronjob.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Use setExactAndAllowWhileIdle for precise timing even in Doze mode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                cronjob.executeAt,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.RTC_WAKEUP,
                cronjob.executeAt,
                pendingIntent
            )
        }
    }

    /**
     * Schedule a recurring task using WorkManager
     */
    private suspend fun scheduleRecurring(cronjob: CronjobEntity) {
        require(cronjob.intervalMinutes != null || cronjob.cronExpression != null) {
            "Either intervalMinutes or cronExpression must be set for RECURRING cronjobs"
        }

        // For now, we only support intervalMinutes
        // Cron expression parsing will be a future enhancement
        val intervalMinutes = cronjob.intervalMinutes ?: 60 // Default to 1 hour

        // Build constraints from JSON
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED) // TODO: Parse from cronjob.constraints
            .build()

        val workRequest = PeriodicWorkRequestBuilder<AgentTaskWorker>(
            intervalMinutes.toLong(),
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    AgentTaskWorker.KEY_CRONJOB_ID to cronjob.id
                )
            )
            .addTag(cronjob.id)
            .build()

        workManager.enqueueUniquePeriodicWork(
            "cronjob_${cronjob.id}",
            ExistingPeriodicWorkPolicy.REPLACE,
            workRequest
        )

        // Store WorkManager ID for cancellation
        cronjobDao.updateWorkManagerId(cronjob.id, workRequest.id.toString())
    }

    /**
     * Cancel an AlarmManager alarm
     */
    private fun cancelAlarm(cronjobId: String) {
        val intent = Intent(context, CronjobAlarmReceiver::class.java).apply {
            action = CronjobAlarmReceiver.ACTION_EXECUTE_CRONJOB
            putExtra(CronjobAlarmReceiver.EXTRA_CRONJOB_ID, cronjobId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            cronjobId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.cancel(pendingIntent)
    }

    /**
     * Validate cronjob configuration
     */
    private fun validate(cronjob: CronjobEntity) {
        when (cronjob.scheduleType) {
            ScheduleType.ONE_TIME -> {
                require(cronjob.executeAt != null) {
                    "executeAt must be set for ONE_TIME cronjobs"
                }
                require(cronjob.executeAt > System.currentTimeMillis()) {
                    "executeAt must be in the future"
                }
            }
            ScheduleType.RECURRING -> {
                require(cronjob.intervalMinutes != null || cronjob.cronExpression != null) {
                    "Either intervalMinutes or cronExpression must be set for RECURRING cronjobs"
                }
                cronjob.intervalMinutes?.let { interval ->
                    require(interval >= 15) {
                        "Minimum interval is 15 minutes for battery optimization"
                    }
                }
            }
            ScheduleType.CONDITIONAL -> {
                // Future enhancement - for now, just allow it
            }
        }
    }
}
