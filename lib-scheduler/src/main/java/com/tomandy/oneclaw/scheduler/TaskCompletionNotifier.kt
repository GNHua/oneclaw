package com.tomandy.oneclaw.scheduler

interface TaskCompletionNotifier {
    suspend fun onTaskCompleted(title: String, summary: String)
    suspend fun onTaskFailed(title: String, error: String)
}
