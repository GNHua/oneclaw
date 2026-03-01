package com.oneclaw.shadow.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.oneclaw.shadow.core.repository.SettingsRepository
import com.oneclaw.shadow.feature.agent.AgentDetailScreen
import com.oneclaw.shadow.feature.agent.AgentListScreen
import com.oneclaw.shadow.feature.chat.ChatScreen
import com.oneclaw.shadow.feature.provider.ProviderDetailScreen
import com.oneclaw.shadow.feature.provider.ProviderListScreen
import com.oneclaw.shadow.feature.provider.SetupScreen
import com.oneclaw.shadow.feature.provider.SettingsScreen
import com.oneclaw.shadow.feature.memory.ui.MemoryScreen
import com.oneclaw.shadow.feature.settings.DataBackupScreen
import com.oneclaw.shadow.feature.settings.GoogleAuthScreen
import com.oneclaw.shadow.feature.skill.ui.SkillEditorScreen
import com.oneclaw.shadow.feature.skill.ui.SkillManagementScreen
import com.oneclaw.shadow.feature.schedule.ScheduledTaskDetailScreen
import com.oneclaw.shadow.feature.schedule.ScheduledTaskEditScreen
import com.oneclaw.shadow.feature.schedule.ScheduledTaskListScreen
import com.oneclaw.shadow.feature.tool.ToolManagementScreen
import com.oneclaw.shadow.feature.usage.UsageStatisticsScreen
import com.oneclaw.shadow.feature.bridge.BridgeSettingsScreen
import com.oneclaw.shadow.feature.file.FileBrowserScreen
import com.oneclaw.shadow.feature.file.FilePreviewScreen
import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import org.koin.compose.koinInject

/**
 * Safely navigate only when the current back stack entry is RESUMED,
 * preventing duplicate navigation on rapid clicks.
 */
private fun NavController.safeNavigate(route: String, builder: (androidx.navigation.NavOptionsBuilder.() -> Unit)? = null) {
    if (currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) {
        if (builder != null) navigate(route, builder) else navigate(route)
    }
}

private fun NavController.safePopBackStack() {
    if (currentBackStackEntry?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) == true) {
        popBackStack()
    }
}

@Suppress("LongParameterList")
@Composable
fun AppNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier,
    notificationSessionId: String? = null
) {
    val settingsRepository: SettingsRepository = koinInject()

    // First-launch detection: read setup flag and navigate accordingly
    LaunchedEffect(Unit) {
        val hasCompletedSetup = settingsRepository.getBoolean("has_completed_setup", false)
        if (!hasCompletedSetup) {
            navController.navigate(Route.Setup.path) {
                popUpTo(Route.Chat.path) { inclusive = true }
            }
        } else if (notificationSessionId != null) {
            // RFC-008: Navigate to session from notification tap
            navController.navigate(Route.ChatSession.create(notificationSessionId)) {
                popUpTo(Route.Chat.path) { inclusive = true }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = Route.Chat.path,
        modifier = modifier
    ) {
        composable(Route.Chat.path) {
            ChatScreen(
                onNavigateToSettings = { navController.safeNavigate(Route.Settings.path) }
            )
        }

        composable(Route.ChatSession.PATH) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            ChatScreen(
                onNavigateToSettings = { navController.safeNavigate(Route.Settings.path) }
            )
        }

        composable(Route.AgentList.path) {
            AgentListScreen(
                onAgentClick = { agentId ->
                    navController.safeNavigate(Route.AgentDetail.create(agentId))
                },
                onCreateAgent = { navController.safeNavigate(Route.AgentCreate.path) },
                onNavigateBack = { navController.safePopBackStack() }
            )
        }

        composable(Route.AgentDetail.PATH) { backStackEntry ->
            AgentDetailScreen(
                onNavigateBack = { navController.safePopBackStack() }
            )
        }

        composable(Route.AgentCreate.path) {
            AgentDetailScreen(
                onNavigateBack = { navController.safePopBackStack() }
            )
        }

        composable(Route.ProviderList.path) {
            ProviderListScreen(
                onProviderClick = { providerId ->
                    navController.safeNavigate(Route.ProviderDetail.create(providerId))
                },
                onNavigateBack = { navController.safePopBackStack() }
            )
        }

        composable(Route.ProviderDetail.PATH) {
            ProviderDetailScreen(
                onNavigateBack = { navController.safePopBackStack() }
            )
        }

        composable(Route.Setup.path) {
            SetupScreen(
                onComplete = {
                    navController.safeNavigate(Route.Chat.path) {
                        popUpTo(Route.Setup.path) { inclusive = true }
                    }
                }
            )
        }

        composable(Route.Settings.path) {
            SettingsScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onManageProviders = { navController.safeNavigate(Route.ProviderList.path) },
                onManageAgents = { navController.safeNavigate(Route.AgentList.path) },
                onManageTools = { navController.safeNavigate(Route.ManageTools.path) },
                onUsageStatistics = { navController.safeNavigate(Route.UsageStatistics.path) },
                onDataBackup = { navController.safeNavigate(Route.DataBackup.path) },
                onMemory = { navController.safeNavigate(Route.Memory.path) },
                onSkills = { navController.safeNavigate(Route.SkillManagement.path) },
                onScheduledTasks = { navController.safeNavigate(Route.ScheduleList.path) },
                onFiles = { navController.safeNavigate(Route.FileBrowser.path) },
                onGoogleAccount = { navController.safeNavigate(Route.GoogleAuth.path) },
                onBridgeSettings = { navController.safeNavigate(Route.BridgeSettings.path) }
            )
        }

        composable(Route.BridgeSettings.path) {
            BridgeSettingsScreen(
                onNavigateBack = { navController.safePopBackStack() }
            )
        }

        composable(Route.ManageTools.path) {
            ToolManagementScreen(
                onNavigateBack = { navController.safePopBackStack() }
            )
        }

        composable(Route.UsageStatistics.path) {
            UsageStatisticsScreen(
                onNavigateBack = { navController.safePopBackStack() }
            )
        }

        composable(Route.DataBackup.path) {
            val context = androidx.compose.ui.platform.LocalContext.current
            DataBackupScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onRestartApp = {
                    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                    Runtime.getRuntime().exit(0)
                }
            )
        }

        composable(Route.Memory.path) {
            MemoryScreen(
                onNavigateBack = { navController.safePopBackStack() }
            )
        }

        composable(Route.SkillManagement.path) {
            SkillManagementScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onCreateSkill = { navController.safeNavigate(Route.SkillCreate.path) },
                onEditSkill = { skillName ->
                    navController.safeNavigate(Route.SkillEdit.create(skillName))
                }
            )
        }

        composable(Route.SkillCreate.path) {
            SkillEditorScreen(
                skillName = null,
                onNavigateBack = { navController.safePopBackStack() }
            )
        }

        composable(Route.SkillEdit.PATH) { backStackEntry ->
            val skillName = backStackEntry.arguments?.getString("skillName")
            SkillEditorScreen(
                skillName = skillName,
                onNavigateBack = { navController.safePopBackStack() }
            )
        }

        composable(Route.ScheduleList.path) {
            ScheduledTaskListScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onCreateTask = { navController.safeNavigate(Route.ScheduleCreate.path) },
                onEditTask = { taskId ->
                    navController.safeNavigate(Route.ScheduleDetail.create(taskId))
                }
            )
        }

        composable(Route.ScheduleCreate.path) {
            ScheduledTaskEditScreen(
                onNavigateBack = { navController.safePopBackStack() }
            )
        }

        composable(Route.ScheduleEdit.PATH) {
            ScheduledTaskEditScreen(
                onNavigateBack = { navController.safePopBackStack() }
            )
        }

        composable(Route.ScheduleDetail.PATH) { backStackEntry ->
            val taskId = backStackEntry.arguments?.getString("taskId") ?: return@composable
            ScheduledTaskDetailScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onEditTask = { id -> navController.safeNavigate(Route.ScheduleEdit.create(id)) },
                onNavigateToSession = { sessionId ->
                    navController.safeNavigate(Route.ChatSession.create(sessionId))
                }
            )
        }

        composable(Route.FileBrowser.path) {
            FileBrowserScreen(
                onNavigateBack = { navController.safePopBackStack() },
                onPreviewFile = { relativePath ->
                    navController.safeNavigate(Route.FilePreview.create(relativePath))
                }
            )
        }

        composable(
            route = Route.FilePreview.ROUTE_PATH,
            arguments = listOf(navArgument("path") { type = NavType.StringType })
        ) {
            FilePreviewScreen(
                onNavigateBack = { navController.safePopBackStack() }
            )
        }

        composable(Route.GoogleAuth.path) {
            GoogleAuthScreen(
                onNavigateBack = { navController.safePopBackStack() }
            )
        }
    }
}
