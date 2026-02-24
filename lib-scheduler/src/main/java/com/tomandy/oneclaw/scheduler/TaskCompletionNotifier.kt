package com.tomandy.oneclaw.scheduler

interface TaskCompletionNotifier {
    suspend fun onTaskCompleted(instruction: String, summary: String)
    suspend fun onTaskFailed(instruction: String, error: String)
}
