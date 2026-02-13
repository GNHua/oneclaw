package com.tomandy.palmclaw.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.tomandy.palmclaw.PalmClawApp
import com.tomandy.palmclaw.ui.chat.ChatScreen
import com.tomandy.palmclaw.ui.chat.ChatViewModel
import com.tomandy.palmclaw.ui.cronjobs.CronjobsScreen
import com.tomandy.palmclaw.ui.cronjobs.CronjobsViewModel
import com.tomandy.palmclaw.ui.settings.SettingsScreen
import com.tomandy.palmclaw.ui.settings.SettingsViewModel

/**
 * Navigation routes for the PalmClaw application.
 */
enum class Screen(val route: String) {
    Chat("chat"),
    Settings("settings"),
    Cronjobs("cronjobs")
}

/**
 * Navigation graph for PalmClaw.
 *
 * Defines the navigation structure with two main screens:
 * - Chat: Main conversation screen
 * - Settings: API key management
 *
 * ViewModels are created using remember to ensure they survive recomposition
 * but are recreated when navigating away and back.
 *
 * @param navController The navigation controller
 * @param app The application instance for dependency injection
 * @param modifier Modifier for styling
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PalmClawNavGraph(
    navController: NavHostController,
    app: PalmClawApp,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Chat.route,
        modifier = modifier
    ) {
        composable(Screen.Chat.route) {
            val viewModel = remember {
                ChatViewModel(
                    toolRegistry = app.toolRegistry,
                    toolExecutor = app.toolExecutor,
                    messageStore = app.messageStore,
                    messageDao = app.database.messageDao(),
                    conversationDao = app.database.conversationDao(),
                    modelPreferences = app.modelPreferences,
                    getCurrentClient = { app.getCurrentLlmClient() },
                    getCurrentProvider = { app.selectedProvider.value }
                )
            }

            ChatScreen(
                viewModel = viewModel,
                app = app,
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToCronjobs = {
                    navController.navigate(Screen.Cronjobs.route)
                }
            )
        }

        composable(Screen.Settings.route) {
            val scope = rememberCoroutineScope()
            val viewModel = remember {
                SettingsViewModel(
                    credentialVault = app.credentialVault,
                    modelPreferences = app.modelPreferences,
                    onApiKeyChanged = {
                        scope.launch {
                            app.reloadApiKeys()
                        }
                    }
                )
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Settings") },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                        }
                    )
                }
            ) { paddingValues ->
                SettingsScreen(
                    viewModel = viewModel,
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }

        composable(Screen.Cronjobs.route) {
            val viewModel = remember {
                CronjobsViewModel(cronjobManager = app.cronjobManager)
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Scheduled Tasks") },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                        }
                    )
                }
            ) { paddingValues ->
                CronjobsScreen(
                    viewModel = viewModel,
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }
    }
}
