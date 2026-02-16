package com.tomandy.palmclaw.backup

import com.tomandy.palmclaw.data.entity.ConversationEntity
import com.tomandy.palmclaw.data.entity.MessageEntity
import com.tomandy.palmclaw.scheduler.data.CronjobEntity
import com.tomandy.palmclaw.scheduler.data.ExecutionLog
import com.tomandy.palmclaw.scheduler.data.ExecutionStatus
import com.tomandy.palmclaw.scheduler.data.ScheduleType
import kotlinx.serialization.Serializable

@Serializable
data class BackupManifest(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val appVersionCode: Int = 0,
    val appVersionName: String = "",
    val exportTimestamp: Long = System.currentTimeMillis(),
    val includesMedia: Boolean = true,
    val stats: BackupStats = BackupStats()
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 1
        const val FILENAME = "manifest.json"
    }
}

@Serializable
data class BackupStats(
    val conversations: Int = 0,
    val messages: Int = 0,
    val cronjobs: Int = 0,
    val executionLogs: Int = 0,
    val mediaFiles: Int = 0
)

@Serializable
data class BackupConversation(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int = 0,
    val lastMessagePreview: String = ""
)

@Serializable
data class BackupMessage(
    val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolCalls: String? = null,
    val imagePaths: String? = null,
    val audioPaths: String? = null,
    val videoPaths: String? = null
)

@Serializable
data class BackupCronjob(
    val id: String,
    val title: String,
    val instruction: String,
    val scheduleType: String,
    val cronExpression: String? = null,
    val executeAt: Long? = null,
    val intervalMinutes: Int? = null,
    val constraints: String = "{}",
    val enabled: Boolean = true,
    val createdAt: Long = 0,
    val lastExecutedAt: Long? = null,
    val executionCount: Int = 0,
    val maxExecutions: Int? = null,
    val notifyOnCompletion: Boolean = true,
    val conversationId: String? = null
)

@Serializable
data class BackupExecutionLog(
    val id: Long,
    val cronjobId: String,
    val startedAt: Long,
    val completedAt: Long? = null,
    val status: String,
    val resultSummary: String? = null,
    val errorMessage: String? = null
)

@Serializable
data class BackupPreferences(
    val strings: Map<String, String> = emptyMap(),
    val ints: Map<String, Int> = emptyMap(),
    val floats: Map<String, Float> = emptyMap(),
    val booleans: Map<String, Boolean> = emptyMap(),
    val longs: Map<String, Long> = emptyMap()
)

// Entity -> Backup mapping

fun ConversationEntity.toBackup() = BackupConversation(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    messageCount = messageCount,
    lastMessagePreview = lastMessagePreview
)

fun BackupConversation.toEntity() = ConversationEntity(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    messageCount = messageCount,
    lastMessagePreview = lastMessagePreview
)

fun MessageEntity.toBackup(filesDir: String) = BackupMessage(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    timestamp = timestamp,
    toolCallId = toolCallId,
    toolName = toolName,
    toolCalls = toolCalls,
    imagePaths = imagePaths?.replace(filesDir, ""),
    audioPaths = audioPaths?.replace(filesDir, ""),
    videoPaths = videoPaths?.replace(filesDir, "")
)

fun BackupMessage.toEntity(filesDir: String) = MessageEntity(
    id = id,
    conversationId = conversationId,
    role = role,
    content = content,
    timestamp = timestamp,
    toolCallId = toolCallId,
    toolName = toolName,
    toolCalls = toolCalls,
    imagePaths = imagePaths?.replace("/chat_images/", "${filesDir}/chat_images/"),
    audioPaths = audioPaths?.replace("/chat_audio/", "${filesDir}/chat_audio/"),
    videoPaths = videoPaths?.replace("/chat_video/", "${filesDir}/chat_video/")
)

fun CronjobEntity.toBackup() = BackupCronjob(
    id = id,
    title = title,
    instruction = instruction,
    scheduleType = scheduleType.name,
    cronExpression = cronExpression,
    executeAt = executeAt,
    intervalMinutes = intervalMinutes,
    constraints = constraints,
    enabled = enabled,
    createdAt = createdAt,
    lastExecutedAt = lastExecutedAt,
    executionCount = executionCount,
    maxExecutions = maxExecutions,
    notifyOnCompletion = notifyOnCompletion,
    conversationId = conversationId
)

fun BackupCronjob.toEntity() = CronjobEntity(
    id = id,
    title = title,
    instruction = instruction,
    scheduleType = ScheduleType.valueOf(scheduleType),
    cronExpression = cronExpression,
    executeAt = executeAt,
    intervalMinutes = intervalMinutes,
    constraints = constraints,
    enabled = false, // Always disabled on import -- WorkManager state is device-local
    createdAt = createdAt,
    lastExecutedAt = lastExecutedAt,
    executionCount = executionCount,
    maxExecutions = maxExecutions,
    notifyOnCompletion = notifyOnCompletion,
    workManagerId = null, // Stale reference, clear it
    conversationId = conversationId
)

fun ExecutionLog.toBackup() = BackupExecutionLog(
    id = id,
    cronjobId = cronjobId,
    startedAt = startedAt,
    completedAt = completedAt,
    status = status.name,
    resultSummary = resultSummary,
    errorMessage = errorMessage
)

fun BackupExecutionLog.toEntity() = ExecutionLog(
    id = id,
    cronjobId = cronjobId,
    startedAt = startedAt,
    completedAt = completedAt,
    status = ExecutionStatus.valueOf(status),
    resultSummary = resultSummary,
    errorMessage = errorMessage
)
