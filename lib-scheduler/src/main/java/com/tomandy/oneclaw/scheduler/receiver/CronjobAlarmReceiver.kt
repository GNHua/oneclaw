package com.tomandy.oneclaw.scheduler.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.tomandy.oneclaw.scheduler.service.AgentExecutionService

/**
 * BroadcastReceiver that handles AlarmManager alarms for one-time cronjobs
 *
 * When the alarm fires, this receiver starts a foreground service
 * to execute the agent task. The app can be completely killed
 * before the alarm - Android will wake it up when needed.
 */
class CronjobAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_EXECUTE_CRONJOB) {
            return
        }

        val cronjobId = intent.getStringExtra(EXTRA_CRONJOB_ID) ?: return

        // Start the foreground service to execute the task
        // We use a foreground service because:
        // 1. Android 8+ requires foreground services for background work
        // 2. It prevents the process from being killed mid-execution
        // 3. It gives us more than the 10-second BroadcastReceiver limit
        val serviceIntent = Intent(context, AgentExecutionService::class.java).apply {
            action = AgentExecutionService.ACTION_EXECUTE_TASK
            putExtra(AgentExecutionService.EXTRA_CRONJOB_ID, cronjobId)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    companion object {
        const val ACTION_EXECUTE_CRONJOB = "com.tomandy.oneclaw.scheduler.EXECUTE_CRONJOB"
        const val EXTRA_CRONJOB_ID = "cronjob_id"
    }
}
