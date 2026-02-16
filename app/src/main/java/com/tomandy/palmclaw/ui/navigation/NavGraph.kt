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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.tomandy.palmclaw.data.ConversationPreferences
import com.tomandy.palmclaw.llm.LlmClientProvider
import com.tomandy.palmclaw.llm.LlmProvider
import com.tomandy.palmclaw.navigation.NavigationState
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
import com.tomandy.palmclaw.ui.settings.SkillsScreen
import com.tomandy.palmclaw.skill.SkillPreferences
import com.tomandy.palmclaw.skill.SkillRepository
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

enum class Screen(val route: String) {
    Chat("chat"),
    Settings("settings"),
    Providers("settings/providers"),
    Plugins("settings/plugins"),
    Skills("settings/skills"),
    Cronjobs("cronjobs"),
    History("history")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PalmClawNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    val llmClientProvider: LlmClientProvider = koinInject()
    val conversationPreferences: ConversationPreferences = koinInject()
    val navigationState: NavigationState = koinInject()

    val modelPreferences: com.tomandy.palmclaw.data.ModelPreferences = koinInject()
    val settingsViewModel: SettingsViewModel = koinViewModel()

    // ChatViewModel is created once and survives across navigation.
    // It loads the last active conversation from ConversationPreferences.
    val activeId = remember { conversationPreferences.getActiveConversationId() }
    val chatViewModel: ChatViewModel = koinViewModel { parametersOf(activeId) }

    // Model selection state (shared between Settings and Chat)
    var availableModels by remember { mutableStateOf<List<Pair<String, LlmProvider>>>(emptyList()) }
    var selectedModel by remember { mutableStateOf(modelPreferences.getSelectedModel() ?: "gpt-4o-mini") }

    LaunchedEffect(Unit) {
        llmClientProvider.reloadApiKeys()
        availableModels = llmClientProvider.getAvailableModels()
        if (availableModels.isNotEmpty() && selectedModel !in availableModels.map { it.first }) {
            selectedModel = availableModels.first().first
            llmClientProvider.setModelAndProvider(selectedModel)
        }
    }

    // Handle notification tap: navigate to the target conversation
    val pendingConvId by navigationState.pendingConversationId.collectAsState()
    LaunchedEffect(pendingConvId) {
        pendingConvId?.let { convId ->
            chatViewModel.loadConversation(convId)
            navController.popBackStack(Screen.Chat.route, inclusive = false)
            navigationState.pendingConversationId.value = null
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Chat.route,
        modifier = modifier
    ) {
        composable(Screen.Chat.route) {
            ChatScreen(
                viewModel = chatViewModel,
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
                    onNavigateToSkills = {
                        navController.navigate(Screen.Skills.route)
                    },
                    modelPreferences = modelPreferences,
                    availableModels = availableModels,
                    selectedModel = selectedModel,
                    onModelSelected = { model ->
                        selectedModel = model
                        llmClientProvider.setModelAndProvider(model)
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

        composable(Screen.Skills.route) {
            val skillRepository: SkillRepository = koinInject()
            val skillPreferences: SkillPreferences = koinInject()

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Skills") },
                        navigationIcon = {
                            IconButton(onClick = { navController.popBackStack() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                        }
                    )
                }
            ) { paddingValues ->
                SkillsScreen(
                    skillRepository = skillRepository,
                    skillPreferences = skillPreferences,
                    onSkillToggled = { name, enabled ->
                        skillPreferences.setSkillEnabled(name, enabled)
                    },
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }

        composable(Screen.Cronjobs.route) {
            val viewModel: CronjobsViewModel = koinViewModel()

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
            val viewModel: ConversationHistoryViewModel = koinViewModel()

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
