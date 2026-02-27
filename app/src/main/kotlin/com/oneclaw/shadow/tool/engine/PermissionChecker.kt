package com.oneclaw.shadow.tool.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Handles Android runtime permission checking and requesting.
 *
 * The coroutine suspends when permissions need to be requested and resumes
 * when the user responds to the system dialog.
 *
 * Lifecycle: bind to Activity in onCreate, unbind in onDestroy.
 */
class PermissionChecker(private val context: Context) {

    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null
    private var pendingContinuation: CancellableContinuation<Boolean>? = null

    /** Called from MainActivity.onCreate to wire up the permission result callback. */
    fun bindToActivity(launcher: ActivityResultLauncher<Array<String>>) {
        this.permissionLauncher = launcher
    }

    /** Called from MainActivity.onDestroy to avoid leaking Activity references. */
    fun unbind() {
        permissionLauncher = null
        pendingContinuation?.cancel()
        pendingContinuation = null
    }

    /**
     * Returns the subset of [permissions] that have not been granted yet.
     * MANAGE_EXTERNAL_STORAGE is checked via Environment.isExternalStorageManager() on API 30+.
     */
    fun getMissingPermissions(permissions: List<String>): List<String> {
        if (permissions.isEmpty()) return emptyList()
        return permissions.filter { permission ->
            if (permission == android.Manifest.permission.MANAGE_EXTERNAL_STORAGE) {
                !hasManageExternalStoragePermission()
            } else {
                ContextCompat.checkSelfPermission(context, permission) !=
                    PackageManager.PERMISSION_GRANTED
            }
        }
    }

    /**
     * Requests the given permissions. Suspends until the user responds.
     * Returns true if ALL permissions were granted.
     *
     * Note: MANAGE_EXTERNAL_STORAGE cannot be requested via the standard dialog.
     * For that permission, call [requestManageExternalStorage] instead and return false
     * so the caller can inform the user to enable it in Settings.
     */
    suspend fun requestPermissions(permissions: List<String>): Boolean {
        // Special case: MANAGE_EXTERNAL_STORAGE must be handled separately
        if (android.Manifest.permission.MANAGE_EXTERNAL_STORAGE in permissions) {
            return false  // Caller should call requestManageExternalStorage() and guide the user
        }

        val launcher = permissionLauncher ?: return false

        return suspendCancellableCoroutine { continuation ->
            pendingContinuation = continuation
            continuation.invokeOnCancellation { pendingContinuation = null }
            launcher.launch(permissions.toTypedArray())
        }
    }

    /**
     * Called by MainActivity when the system permission dialog result arrives.
     * Resumes the suspended coroutine.
     */
    fun onPermissionResult(permissions: Map<String, Boolean>) {
        val allGranted = permissions.values.all { it }
        pendingContinuation?.resume(allGranted)
        pendingContinuation = null
    }

    /** Returns true if MANAGE_EXTERNAL_STORAGE is effectively granted. */
    fun hasManageExternalStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true  // Not needed on Android < 11
        }
    }

    /**
     * Opens the system Settings page so the user can grant MANAGE_EXTERNAL_STORAGE.
     * Returns immediately — callers must guide the user verbally.
     */
    fun requestManageExternalStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
