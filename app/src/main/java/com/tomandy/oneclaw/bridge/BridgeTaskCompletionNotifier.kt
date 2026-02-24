package com.tomandy.oneclaw.bridge

import com.tomandy.oneclaw.scheduler.TaskCompletionNotifier

class BridgeTaskCompletionNotifier : TaskCompletionNotifier {

    override suspend fun onTaskCompleted(instruction: String, summary: String) {
        BridgeBroadcaster.broadcast("[Scheduled Task] $instruction\n\n$summary")
    }

    override suspend fun onTaskFailed(instruction: String, error: String) {
        BridgeBroadcaster.broadcast("[Scheduled Task Failed] $instruction\n\nError: $error")
    }
}
