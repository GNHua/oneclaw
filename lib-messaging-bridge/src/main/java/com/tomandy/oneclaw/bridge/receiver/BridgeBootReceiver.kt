package com.tomandy.oneclaw.bridge.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.tomandy.oneclaw.bridge.BridgePreferences
import com.tomandy.oneclaw.bridge.service.MessagingBridgeService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BridgeBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = BridgePreferences(context)
                if (prefs.hasAnyChannelEnabled()) {
                    MessagingBridgeService.start(context)
                    Log.i(TAG, "Restarted messaging bridge after boot")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart bridge after boot", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "BridgeBootReceiver"
    }
}
