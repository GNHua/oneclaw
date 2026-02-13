package com.tomandy.palmclaw

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.rememberNavController
import com.tomandy.palmclaw.notification.ChatNotificationHelper
import com.tomandy.palmclaw.ui.navigation.PalmClawNavGraph
import com.tomandy.palmclaw.ui.theme.PalmClawTheme

/**
 * Main activity for the PalmClaw application.
 *
 * This activity serves as the entry point and hosts the navigation graph.
 * It uses Jetpack Compose for the entire UI and sets up the navigation controller
 * to manage screen transitions.
 */
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op: notifications are optional */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestNotificationPermission()

        val app = application as PalmClawApp
        handleNotificationIntent(app, intent)

        setContent {
            PalmClawTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    PalmClawNavGraph(
                        navController = navController,
                        app = app
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val app = application as PalmClawApp
        handleNotificationIntent(app, intent)
    }

    private fun handleNotificationIntent(app: PalmClawApp, intent: Intent?) {
        intent?.getStringExtra(ChatNotificationHelper.EXTRA_CONVERSATION_ID)?.let { convId ->
            app.pendingConversationId.value = convId
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}