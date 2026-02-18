package com.tomandy.oneclaw.devicecontrol

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import java.lang.ref.WeakReference

object DeviceControlManager {

    private const val TAG = "DeviceControlManager"

    private var serviceRef: WeakReference<DeviceControlService>? = null
    private var abortCallback: AbortCallback? = null
    private var accessibilityPromptCallback: AccessibilityPromptCallback? = null

    fun registerService(service: DeviceControlService) {
        serviceRef = WeakReference(service)
        Log.i(TAG, "Accessibility service registered")
    }

    fun unregisterService() {
        serviceRef = null
        Log.i(TAG, "Accessibility service unregistered")
    }

    fun isServiceConnected(): Boolean = serviceRef?.get() != null

    fun setAbortCallback(callback: AbortCallback) {
        abortCallback = callback
    }

    fun setAccessibilityPromptCallback(callback: AccessibilityPromptCallback) {
        accessibilityPromptCallback = callback
    }

    fun promptEnableService() {
        accessibilityPromptCallback?.onAccessibilityServiceNeeded()
    }

    fun abortAllExecutions() {
        Log.w(TAG, "Abort triggered via hardware button")
        abortCallback?.abortAllExecutions()
    }

    fun getScreenContent(): String {
        val service = serviceRef?.get()
            ?: return "Error: Accessibility service is not enabled. Please enable OneClaw Device Control in Settings > Accessibility."
        return service.getScreenContent()
    }

    fun tap(text: String?, contentDescription: String?, resourceId: String?, x: Int?, y: Int?): Boolean {
        val service = serviceRef?.get() ?: return false
        return when {
            x != null && y != null -> service.performTapAtCoordinates(x, y)
            else -> service.performTap(text, contentDescription, resourceId)
        }
    }

    fun typeText(text: String, targetHint: String?): Boolean {
        val service = serviceRef?.get() ?: return false
        return service.performSetText(text, targetHint)
    }

    fun swipe(direction: String): Boolean {
        val service = serviceRef?.get() ?: return false
        return service.performSwipe(direction)
    }

    fun pressBack(): Boolean {
        val service = serviceRef?.get() ?: return false
        return service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
    }

    fun pressHome(): Boolean {
        val service = serviceRef?.get() ?: return false
        return service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
    }

    fun launchApp(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app: $packageName", e)
            false
        }
    }

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
