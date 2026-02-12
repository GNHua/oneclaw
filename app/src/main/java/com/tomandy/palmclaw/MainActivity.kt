package com.tomandy.palmclaw

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.rememberNavController
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
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as PalmClawApp

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
}