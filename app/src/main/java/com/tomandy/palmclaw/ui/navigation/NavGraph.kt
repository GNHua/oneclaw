package com.tomandy.palmclaw.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.tomandy.palmclaw.backup.BackupViewModel
import com.tomandy.palmclaw.ui.settings.BackupScreen
import com.tomandy.palmclaw.ui.settings.MemoryDetailScreen
import com.tomandy.palmclaw.ui.settings.MemoryScreen
import com.tomandy.palmclaw.ui.settings.MemoryViewModel
import com.tomandy.palmclaw.ui.settings.PluginsScreen
import com.tomandy.palmclaw.ui.settings.ProvidersScreen
import com.tomandy.palmclaw.ui.settings.SettingsScreen
import com.tomandy.palmclaw.ui.settings.SettingsViewModel
import com.tomandy.palmclaw.ui.settings.SkillEditorScreen
import com.tomandy.palmclaw.ui.settings.AgentProfileEditorScreen
import com.tomandy.palmclaw.ui.settings.AgentProfilesScreen
import com.tomandy.palmclaw.ui.settings.AgentProfilesViewModel
import com.tomandy.palmclaw.ui.settings.GoogleAccountScreen
import com.tomandy.palmclaw.ui.settings.SkillsScreen
import com.tomandy.palmclaw.ui.settings.SkillsViewModel
import com.tomandy.palmclaw.google.OAuthGoogleAuthManager
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

enum class Screen(val route: String) {
    Chat("chat"),
    Settings("settings"),
    Providers("settings/providers"),
    Plugins("settings/plugins"),
    Skills("settings/skills"),
    SkillEditor("settings/skills/editor"),
    Memory("settings/memory"),
    MemoryDetail("settings/memory/detail"),
    Backup("settings/backup"),
    AgentProfiles("settings/agents"),
    AgentProfileEditor("settings/agents/editor"),
    GoogleAccount("settings/google-account"),
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

    // History screen is shown as an overlay instead of a NavHost destination,
    // so the Chat composable never leaves composition during Chat<->History cycles.
    var showHistory by remember { mutableStateOf(false) }

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
            showHistory = false
            navController.popBackStack(Screen.Chat.route, inclusive = false)
            navigationState.pendingConversationId.value = null
        }
    }

    // Handle agent-assisted skill creation/editing
    val pendingSkillSeed by navigationState.pendingSkillSeed.collectAsState()
    LaunchedEffect(pendingSkillSeed) {
        pendingSkillSeed?.let { seed ->
            chatViewModel.newConversation()
            kotlinx.coroutines.delay(100)
            chatViewModel.sendMessage(seed)
            showHistory = false
            navController.popBackStack(Screen.Chat.route, inclusive = false)
            navigationState.pendingSkillSeed.value = null
        }
    }

    // Handle shared text from other apps (ACTION_SEND)
    // Navigate to chat screen; ChatScreen consumes the text to pre-fill input.
    val pendingSharedText by navigationState.pendingSharedText.collectAsState()
    LaunchedEffect(pendingSharedText) {
        if (pendingSharedText != null) {
            showHistory = false
            navController.popBackStack(Screen.Chat.route, inclusive = false)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = Screen.Chat.route,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(Screen.Chat.route) {
                ChatScreen(
                    viewModel = chatViewModel,
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route) { launchSingleTop = true }
                    },
                    onNavigateToCronjobs = {
                        navController.navigate(Screen.Cronjobs.route) { launchSingleTop = true }
                    },
                    onNavigateToHistory = {
                        showHistory = true
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
                            navController.navigate(Screen.Providers.route) { launchSingleTop = true }
                        },
                        onNavigateToPlugins = {
                            navController.navigate(Screen.Plugins.route) { launchSingleTop = true }
                        },
                        onNavigateToSkills = {
                            navController.navigate(Screen.Skills.route) { launchSingleTop = true }
                        },
                        onNavigateToMemory = {
                            navController.navigate(Screen.Memory.route) { launchSingleTop = true }
                        },
                        onNavigateToBackup = {
                            navController.navigate(Screen.Backup.route) { launchSingleTop = true }
                        },
                        onNavigateToAgentProfiles = {
                            navController.navigate(Screen.AgentProfiles.route) { launchSingleTop = true }
                        },
                        onNavigateToGoogleAccount = {
                            navController.navigate(Screen.GoogleAccount.route) { launchSingleTop = true }
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
                val skillsViewModel: SkillsViewModel = koinViewModel()

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
                        viewModel = skillsViewModel,
                        onNavigateToEditor = { skillName ->
                            val route = if (skillName != null) {
                                "${Screen.SkillEditor.route}?skillName=$skillName"
                            } else {
                                Screen.SkillEditor.route
                            }
                            navController.navigate(route) { launchSingleTop = true }
                        },
                        onNavigateToChat = {
                            navController.popBackStack(Screen.Chat.route, inclusive = false)
                        },
                        modifier = Modifier.padding(paddingValues)
                    )
                }
            }

            composable(
                route = "${Screen.SkillEditor.route}?skillName={skillName}",
                arguments = listOf(
                    navArgument("skillName") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val skillName = backStackEntry.arguments?.getString("skillName")
                val skillsViewModel: SkillsViewModel = koinViewModel()

                SkillEditorScreen(
                    viewModel = skillsViewModel,
                    skillName = skillName,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToChat = {
                        navController.popBackStack(Screen.Chat.route, inclusive = false)
                    }
                )
            }

            composable(Screen.Memory.route) {
                val memoryViewModel: MemoryViewModel = koinViewModel()

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Memory") },
                            navigationIcon = {
                                IconButton(onClick = { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    MemoryScreen(
                        viewModel = memoryViewModel,
                        onNavigateToDetail = { relativePath, displayName ->
                            val encoded = java.net.URLEncoder.encode(relativePath, "UTF-8")
                            val encodedName = java.net.URLEncoder.encode(displayName, "UTF-8")
                            navController.navigate(
                                "${Screen.MemoryDetail.route}?path=$encoded&name=$encodedName"
                            ) { launchSingleTop = true }
                        },
                        modifier = Modifier.padding(paddingValues)
                    )
                }
            }

            composable(
                route = "${Screen.MemoryDetail.route}?path={path}&name={name}",
                arguments = listOf(
                    navArgument("path") { type = NavType.StringType },
                    navArgument("name") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val relativePath = java.net.URLDecoder.decode(
                    backStackEntry.arguments?.getString("path") ?: "", "UTF-8"
                )
                val displayName = java.net.URLDecoder.decode(
                    backStackEntry.arguments?.getString("name") ?: "", "UTF-8"
                )
                val memoryViewModel: MemoryViewModel = koinViewModel()

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(displayName) },
                            navigationIcon = {
                                IconButton(onClick = { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    MemoryDetailScreen(
                        viewModel = memoryViewModel,
                        relativePath = relativePath,
                        onDelete = { navController.popBackStack() },
                        modifier = Modifier.padding(paddingValues)
                    )
                }
            }

            composable(Screen.Backup.route) {
                val backupViewModel: BackupViewModel = koinViewModel()

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Backup & Restore") },
                            navigationIcon = {
                                IconButton(onClick = { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    BackupScreen(
                        viewModel = backupViewModel,
                        modifier = Modifier.padding(paddingValues)
                    )
                }
            }

            composable(Screen.AgentProfiles.route) {
                val agentProfilesViewModel: AgentProfilesViewModel = koinViewModel()

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Agent Profiles") },
                            navigationIcon = {
                                IconButton(onClick = { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    AgentProfilesScreen(
                        viewModel = agentProfilesViewModel,
                        onNavigateToEditor = { profileName ->
                            val route = if (profileName != null) {
                                "${Screen.AgentProfileEditor.route}?profileName=$profileName"
                            } else {
                                Screen.AgentProfileEditor.route
                            }
                            navController.navigate(route) { launchSingleTop = true }
                        },
                        modifier = Modifier.padding(paddingValues)
                    )
                }
            }

            composable(
                route = "${Screen.AgentProfileEditor.route}?profileName={profileName}",
                arguments = listOf(
                    navArgument("profileName") {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val profileName = backStackEntry.arguments?.getString("profileName")
                val agentProfilesViewModel: AgentProfilesViewModel = koinViewModel()

                AgentProfileEditorScreen(
                    viewModel = agentProfilesViewModel,
                    profileName = profileName,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.GoogleAccount.route) {
                val googleAuthManager: OAuthGoogleAuthManager = koinInject()

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Google Account") },
                            navigationIcon = {
                                IconButton(onClick = { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    GoogleAccountScreen(
                        googleAuthManager = googleAuthManager,
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
        }

        // History screen shown as an overlay so the Chat composable never leaves
        // composition during Chat<->History navigation cycles.
        if (showHistory) {
            val historyViewModel: ConversationHistoryViewModel = koinViewModel()

            BackHandler { showHistory = false }

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Conversation History") },
                        navigationIcon = {
                            IconButton(onClick = { showHistory = false }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                        }
                    )
                },
                modifier = Modifier.fillMaxSize()
            ) { paddingValues ->
                ConversationHistoryScreen(
                    viewModel = historyViewModel,
                    currentConversationId = chatViewModel.conversationId,
                    onConversationSelected = { convId ->
                        chatViewModel.loadConversation(convId)
                        showHistory = false
                    },
                    modifier = Modifier.padding(paddingValues)
                )
            }
        }
    }
}
