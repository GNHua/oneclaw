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
import com.tomandy.palmclaw.ui.history.ConversationHistoryScreen
import com.tomandy.palmclaw.ui.history.ConversationHistoryViewModel
import com.tomandy.palmclaw.ui.settings.PluginsScreen
import com.tomandy.palmclaw.ui.settings.ProvidersScreen
import com.tomandy.palmclaw.ui.settings.SettingsScreen
import com.tomandy.palmclaw.ui.settings.SettingsViewModel

enum class Screen(val route: String) {
    Chat("chat"),
    Settings("settings"),
    Providers("settings/providers"),
    Plugins("settings/plugins"),
    Cronjobs("cronjobs"),
    History("history")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PalmClawNavGraph(
    navController: NavHostController,
    app: PalmClawApp,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val settingsViewModel = remember {
        SettingsViewModel(
            credentialVault = app.credentialVault,
            modelPreferences = app.modelPreferences,
            pluginPreferences = app.pluginPreferences,
            loadedPlugins = app.pluginEngine.getAllPlugins(),
            onApiKeyChanged = {
                scope.launch {
                    app.reloadApiKeys()
                }
            },
            onPluginToggled = { id, enabled ->
                app.setPluginEnabled(id, enabled)
            }
        )
    }

    // ChatViewModel is created once and survives across navigation.
    // It loads the last active conversation from ConversationPreferences.
    val chatViewModel = remember {
        val activeId = app.conversationPreferences.getActiveConversationId()
        ChatViewModel(
            toolRegistry = app.toolRegistry,
            toolExecutor = app.toolExecutor,
            messageStore = app.messageStore,
            messageDao = app.database.messageDao(),
            conversationDao = app.database.conversationDao(),
            modelPreferences = app.modelPreferences,
            conversationPreferences = app.conversationPreferences,
            getCurrentClient = { app.getCurrentLlmClient() },
            getCurrentProvider = { app.selectedProvider.value },
            conversationId = activeId
        )
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Chat.route,
        modifier = modifier
    ) {
        composable(Screen.Chat.route) {
            ChatScreen(
                viewModel = chatViewModel,
                app = app,
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToCronjobs = {
                    navController.navigate(Screen.Cronjobs.route)
                },
                onNavigateToHistory = {
                    navController.navigate(Screen.History.route)
                }
            )
        }

        composable(Screen.Settings.route) {
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
                    onNavigateToProviders = {
                        navController.navigate(Screen.Providers.route)
                    },
                    onNavigateToPlugins = {
                        navController.navigate(Screen.Plugins.route)
                    },
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }

        composable(Screen.Providers.route) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Providers") },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                        }
                    )
                }
            ) { paddingValues ->
                ProvidersScreen(
                    viewModel = settingsViewModel,
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }

        composable(Screen.Plugins.route) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Plugins") },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                        }
                    )
                }
            ) { paddingValues ->
                PluginsScreen(
                    viewModel = settingsViewModel,
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

        composable(Screen.History.route) {
            val viewModel = remember {
                ConversationHistoryViewModel(
                    conversationDao = app.database.conversationDao(),
                    messageDao = app.database.messageDao()
                )
            }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Conversation History") },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                        }
                    )
                }
            ) { paddingValues ->
                ConversationHistoryScreen(
                    viewModel = viewModel,
                    currentConversationId = chatViewModel.conversationId,
                    onConversationSelected = { convId ->
                        chatViewModel.loadConversation(convId)
                        navController.popBackStack()
                    },
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }
    }
}
