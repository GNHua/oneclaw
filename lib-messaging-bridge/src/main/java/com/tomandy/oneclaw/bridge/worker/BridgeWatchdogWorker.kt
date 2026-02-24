package com.tomandy.oneclaw.bridge.worker

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.tomandy.oneclaw.bridge.BridgePreferences
import com.tomandy.oneclaw.bridge.BridgeStateTracker
import com.tomandy.oneclaw.bridge.service.MessagingBridgeService

class BridgeWatchdogWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = BridgePreferences(applicationContext)
        if (!prefs.hasAnyChannelEnabled()) {
            Log.d(TAG, "No channels enabled, skipping watchdog check")
            return Result.success()
        }

        if (BridgeStateTracker.serviceRunning.value) {
            Log.d(TAG, "Bridge service is running, nothing to do")
            return Result.success()
        }

        Log.i(TAG, "Bridge service is dead, restarting")
        MessagingBridgeService.start(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG = "BridgeWatchdog"
        const val WORK_NAME = "bridge_watchdog"
    }
}
