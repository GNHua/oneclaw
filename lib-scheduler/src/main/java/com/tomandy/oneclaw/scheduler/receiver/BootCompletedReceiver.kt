package com.tomandy.oneclaw.scheduler.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tomandy.oneclaw.scheduler.CronjobManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Re-schedules AlarmManager alarms after device reboot.
 *
 * AlarmManager alarms are cleared by the OS on reboot, so any enabled
 * one-time tasks with a future executeAt need to be re-registered.
 * WorkManager tasks survive reboots automatically and are not affected.
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val manager = CronjobManager(context)
                val count = manager.rescheduleAlarms()
                Log.i(TAG, "Re-scheduled $count alarm(s) after boot")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-schedule alarms after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
