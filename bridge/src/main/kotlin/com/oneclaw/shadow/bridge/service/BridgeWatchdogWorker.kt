package com.oneclaw.shadow.bridge.service

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.oneclaw.shadow.bridge.BridgePreferences
import com.oneclaw.shadow.bridge.BridgeStateTracker

class BridgeWatchdogWorker(
    private val context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val preferences = BridgePreferences(context)
        val shouldRun = preferences.isBridgeEnabled() && preferences.hasAnyChannelEnabled()
        val isRunning = BridgeStateTracker.serviceRunning.value

        Log.d(TAG, "Watchdog check: shouldRun=$shouldRun, isRunning=$isRunning")

        if (shouldRun && !isRunning) {
            Log.w(TAG, "Bridge should be running but isn't -- restarting")
            MessagingBridgeService.start(context)
        }

        return Result.success()
    }

    companion object {
        private const val TAG = "BridgeWatchdogWorker"
        const val WORK_NAME = "bridge_watchdog"
    }
}
