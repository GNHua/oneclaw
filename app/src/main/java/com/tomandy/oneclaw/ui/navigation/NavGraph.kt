package com.tomandy.oneclaw.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import com.tomandy.oneclaw.ui.settings.SystemPromptEditorScreen
import com.tomandy.oneclaw.ui.settings.InstructionsEditorScreen
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
import com.tomandy.oneclaw.data.ConversationPreferences
import com.tomandy.oneclaw.llm.LlmClientProvider
import com.tomandy.oneclaw.llm.LlmProvider
import com.tomandy.oneclaw.navigation.NavigationState
import com.tomandy.oneclaw.ui.chat.ChatScreen
import com.tomandy.oneclaw.ui.chat.ChatViewModel
import com.tomandy.oneclaw.ui.cronjobs.CronjobDetailScreen
import com.tomandy.oneclaw.ui.cronjobs.CronjobsScreen
import com.tomandy.oneclaw.ui.cronjobs.CronjobsViewModel
import com.tomandy.oneclaw.ui.history.ConversationHistoryScreen
import com.tomandy.oneclaw.ui.history.ConversationHistoryViewModel
import com.tomandy.oneclaw.backup.BackupViewModel
import com.tomandy.oneclaw.ui.settings.BackupScreen
import com.tomandy.oneclaw.ui.settings.MemoryDetailScreen
import com.tomandy.oneclaw.ui.settings.MemoryScreen
import com.tomandy.oneclaw.ui.settings.MemoryViewModel
import com.tomandy.oneclaw.ui.settings.PluginsScreen
import com.tomandy.oneclaw.ui.settings.ProvidersScreen
import com.tomandy.oneclaw.ui.settings.SettingsScreen
import com.tomandy.oneclaw.ui.settings.SettingsViewModel
import com.tomandy.oneclaw.ui.settings.SkillEditorScreen
import com.tomandy.oneclaw.ui.settings.AppearanceScreen
import com.tomandy.oneclaw.ui.settings.AgentProfileEditorScreen
import com.tomandy.oneclaw.ui.settings.AgentProfilesScreen
import com.tomandy.oneclaw.ui.settings.AgentProfilesViewModel
import com.tomandy.oneclaw.ui.settings.GoogleAccountScreen
import com.tomandy.oneclaw.ui.settings.SkillsScreen
import com.tomandy.oneclaw.ui.settings.SkillsViewModel
import com.tomandy.oneclaw.google.AntigravityAuthManager
import com.tomandy.oneclaw.google.OAuthGoogleAuthManager
import androidx.lifecycle.compose.dropUnlessResumed
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
    SystemPromptEditor("settings/agents/editor/system-prompt"),
    SkillInstructionsEditor("settings/skills/editor/instructions"),
    GoogleAccount("settings/google-account"),
    Appearance("settings/appearance"),
    Cronjobs("cronjobs"),
    CronjobDetail("cronjobs/detail"),
    History("history")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OneClawNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    val llmClientProvider: LlmClientProvider = koinInject()
    val conversationPreferences: ConversationPreferences = koinInject()
    val navigationState: NavigationState = koinInject()

    val modelPreferences: com.tomandy.oneclaw.data.ModelPreferences = koinInject()
    val settingsViewModel: SettingsViewModel = koinViewModel()

    // ChatViewModel is created once and survives across navigation.
    // It loads the last active conversation from ConversationPreferences.
    val activeId = remember { conversationPreferences.getActiveConversationId() }
    val chatViewModel: ChatViewModel = koinViewModel { parametersOf(activeId) }

    // Model selection state (shared between Settings and Chat)
    var availableModels by remember { mutableStateOf<List<Pair<String, LlmProvider>>>(emptyList()) }
    var selectedModel by remember { mutableStateOf(modelPreferences.getSelectedModel() ?: "gpt-4o-mini") }

    val providers by settingsViewModel.providers.collectAsState()

    LaunchedEffect(providers) {
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

    // Handle agent-assisted skill creation/editing
    val pendingSkillSeed by navigationState.pendingSkillSeed.collectAsState()
    LaunchedEffect(pendingSkillSeed) {
        pendingSkillSeed?.let { seed ->
            chatViewModel.newConversation()
            kotlinx.coroutines.delay(100)
            chatViewModel.sendMessage(seed)
            navController.popBackStack(Screen.Chat.route, inclusive = false)
            navigationState.pendingSkillSeed.value = null
        }
    }

    // Handle shared text from other apps (ACTION_SEND)
    // Navigate to chat screen; ChatScreen consumes the text to pre-fill input.
    val pendingSharedText by navigationState.pendingSharedText.collectAsState()
    LaunchedEffect(pendingSharedText) {
        if (pendingSharedText != null) {
            navController.popBackStack(Screen.Chat.route, inclusive = false)
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.Chat.route,
        modifier = modifier.fillMaxSize()
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
                        navController.navigate(Screen.History.route) { launchSingleTop = true }
                    },
                    onNewConversation = {
                        chatViewModel.newConversation()
                    }
                )
            }

            composable(Screen.Settings.route) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Settings") },
                            navigationIcon = {
                                IconButton(onClick = dropUnlessResumed { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    Column(Modifier.fillMaxSize().padding(paddingValues)) {

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
                            onNavigateToAppearance = {
                                navController.navigate(Screen.Appearance.route) { launchSingleTop = true }
                            },
                            modelPreferences = modelPreferences,
                            availableModels = availableModels,
                            selectedModel = selectedModel,
                            onModelSelected = { model ->
                                selectedModel = model
                                llmClientProvider.setModelAndProvider(model)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            composable(Screen.Providers.route) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Providers") },
                            navigationIcon = {
                                IconButton(onClick = dropUnlessResumed { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    Column(Modifier.fillMaxSize().padding(paddingValues)) {

                        ProvidersScreen(
                            viewModel = settingsViewModel,
                            antigravityAuthManager = koinInject<AntigravityAuthManager>(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            composable(Screen.Plugins.route) {
                val pluginsList by settingsViewModel.plugins.collectAsState()
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Plugins (${pluginsList.size})") },
                            navigationIcon = {
                                IconButton(onClick = dropUnlessResumed { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    Column(Modifier.fillMaxSize().padding(paddingValues)) {

                        PluginsScreen(
                            viewModel = settingsViewModel,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            composable(Screen.Skills.route) {
                val skillsViewModel: SkillsViewModel = koinViewModel()
                val skillsList by skillsViewModel.skills.collectAsState()

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Skills (${skillsList.size})") },
                            navigationIcon = {
                                IconButton(onClick = dropUnlessResumed { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    Column(Modifier.fillMaxSize().padding(paddingValues)) {

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
                            modifier = Modifier.weight(1f)
                        )
                    }
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
                    onNavigateBack = dropUnlessResumed { navController.popBackStack() },
                    onNavigateToChat = {
                        navController.popBackStack(Screen.Chat.route, inclusive = false)
                    },
                    onNavigateToInstructions = { readOnly ->
                        navController.navigate(
                            "${Screen.SkillInstructionsEditor.route}?readOnly=$readOnly"
                        ) { launchSingleTop = true }
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
                                IconButton(onClick = dropUnlessResumed { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    Column(Modifier.fillMaxSize().padding(paddingValues)) {

                        MemoryScreen(
                            viewModel = memoryViewModel,
                            onNavigateToDetail = { relativePath, displayName ->
                                val encoded = java.net.URLEncoder.encode(relativePath, "UTF-8")
                                val encodedName = java.net.URLEncoder.encode(displayName, "UTF-8")
                                navController.navigate(
                                    "${Screen.MemoryDetail.route}?path=$encoded&name=$encodedName"
                                ) { launchSingleTop = true }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
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
                var showRawMarkdown by remember { mutableStateOf(false) }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(displayName) },
                            navigationIcon = {
                                IconButton(onClick = dropUnlessResumed { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            },
                            actions = {
                                IconButton(onClick = { showRawMarkdown = !showRawMarkdown }) {
                                    Icon(
                                        imageVector = if (showRawMarkdown)
                                            Icons.Default.Description
                                        else
                                            Icons.Default.Code,
                                        contentDescription = if (showRawMarkdown) "Show rendered" else "Show raw"
                                    )
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    Column(Modifier.fillMaxSize().padding(paddingValues)) {

                        MemoryDetailScreen(
                            viewModel = memoryViewModel,
                            relativePath = relativePath,
                            showRaw = showRawMarkdown,
                            onDelete = dropUnlessResumed { navController.popBackStack() },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            composable(Screen.Backup.route) {
                val backupViewModel: BackupViewModel = koinViewModel()

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Backup & Restore") },
                            navigationIcon = {
                                IconButton(onClick = dropUnlessResumed { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    Column(Modifier.fillMaxSize().padding(paddingValues)) {

                        BackupScreen(
                            viewModel = backupViewModel,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            composable(Screen.AgentProfiles.route) {
                val agentProfilesViewModel: AgentProfilesViewModel = koinViewModel()

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Agent Profiles") },
                            navigationIcon = {
                                IconButton(onClick = dropUnlessResumed { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    Column(Modifier.fillMaxSize().padding(paddingValues)) {

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
                            modifier = Modifier.weight(1f)
                        )
                    }
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
                    onNavigateBack = dropUnlessResumed { navController.popBackStack() },
                    onNavigateToSystemPrompt = dropUnlessResumed {
                        navController.navigate(Screen.SystemPromptEditor.route) { launchSingleTop = true }
                    }
                )
            }

            composable(Screen.SystemPromptEditor.route) {
                val parentEntry = navController.previousBackStackEntry!!
                val viewModel: AgentProfilesViewModel = koinViewModel(
                    viewModelStoreOwner = parentEntry
                )
                SystemPromptEditorScreen(
                    viewModel = viewModel,
                    onNavigateBack = dropUnlessResumed { navController.popBackStack() }
                )
            }

            composable(
                route = "${Screen.SkillInstructionsEditor.route}?readOnly={readOnly}",
                arguments = listOf(
                    navArgument("readOnly") {
                        type = NavType.BoolType
                        defaultValue = false
                    }
                )
            ) { backStackEntry ->
                val readOnly = backStackEntry.arguments?.getBoolean("readOnly") ?: false
                val parentEntry = navController.previousBackStackEntry!!
                val viewModel: SkillsViewModel = koinViewModel(
                    viewModelStoreOwner = parentEntry
                )
                InstructionsEditorScreen(
                    viewModel = viewModel,
                    readOnly = readOnly,
                    onNavigateBack = dropUnlessResumed { navController.popBackStack() }
                )
            }

            composable(Screen.GoogleAccount.route) {
                val oauthAuthManager: OAuthGoogleAuthManager = koinInject()

                Scaffold(
                    contentWindowInsets = WindowInsets(0),
                    topBar = {
                        TopAppBar(
                            title = { Text("Google Account") },
                            navigationIcon = {
                                IconButton(onClick = dropUnlessResumed { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    Column(Modifier.fillMaxSize().padding(paddingValues)) {

                        GoogleAccountScreen(
                            oauthAuthManager = oauthAuthManager,
                            onSignInChanged = { signedIn ->
                                settingsViewModel.onGoogleSignInChanged(signedIn)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            composable(Screen.Appearance.route) {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Appearance") },
                            navigationIcon = {
                                IconButton(onClick = dropUnlessResumed { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    Column(Modifier.fillMaxSize().padding(paddingValues)) {

                        AppearanceScreen(
                            modelPreferences = modelPreferences,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            composable(Screen.Cronjobs.route) {
                val viewModel: CronjobsViewModel = koinViewModel()

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Scheduled Tasks") },
                            navigationIcon = {
                                IconButton(onClick = dropUnlessResumed { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    Column(Modifier.fillMaxSize().padding(paddingValues)) {

                        CronjobsScreen(
                            viewModel = viewModel,
                            onNavigateToDetail = { cronjobId ->
                                navController.navigate(
                                    "${Screen.CronjobDetail.route}?id=$cronjobId"
                                ) { launchSingleTop = true }
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            composable(
                route = "${Screen.CronjobDetail.route}?id={cronjobId}",
                arguments = listOf(
                    navArgument("cronjobId") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val cronjobId = backStackEntry.arguments?.getString("cronjobId") ?: return@composable
                val viewModel: CronjobsViewModel = koinViewModel()

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Task Details") },
                            navigationIcon = {
                                IconButton(onClick = dropUnlessResumed { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    Column(Modifier.fillMaxSize().padding(paddingValues)) {

                        CronjobDetailScreen(
                            viewModel = viewModel,
                            cronjobId = cronjobId,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            composable(Screen.History.route) {
                val historyViewModel: ConversationHistoryViewModel = koinViewModel()

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Conversation History") },
                            navigationIcon = {
                                IconButton(onClick = dropUnlessResumed { navController.popBackStack() }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                                }
                            }
                        )
                    }
                ) { paddingValues ->
                    Column(Modifier.fillMaxSize().padding(paddingValues)) {

                        ConversationHistoryScreen(
                            viewModel = historyViewModel,
                            currentConversationId = chatViewModel.conversationId,
                            onConversationSelected = { convId ->
                                chatViewModel.loadConversation(convId)
                                navController.popBackStack(Screen.Chat.route, inclusive = false)
                            },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
}
