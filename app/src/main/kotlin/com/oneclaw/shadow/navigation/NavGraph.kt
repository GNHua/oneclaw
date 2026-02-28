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
import com.oneclaw.shadow.feature.usage.UsageStatisticsScreen
import android.content.Intent
import org.koin.compose.koinInject

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
                onNavigateToSettings = { navController.navigate(Route.Settings.path) }
            )
        }

        composable(Route.ChatSession.PATH) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            ChatScreen(
                onNavigateToSettings = { navController.navigate(Route.Settings.path) }
            )
        }

        composable(Route.AgentList.path) {
            AgentListScreen(
                onAgentClick = { agentId ->
                    navController.navigate(Route.AgentDetail.create(agentId))
                },
                onCreateAgent = { navController.navigate(Route.AgentCreate.path) },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Route.AgentDetail.PATH) { backStackEntry ->
            AgentDetailScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Route.AgentCreate.path) {
            AgentDetailScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Route.ProviderList.path) {
            ProviderListScreen(
                onProviderClick = { providerId ->
                    navController.navigate(Route.ProviderDetail.create(providerId))
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Route.ProviderDetail.PATH) {
            ProviderDetailScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Route.Setup.path) {
            SetupScreen(
                onComplete = {
                    navController.navigate(Route.Chat.path) {
                        popUpTo(Route.Setup.path) { inclusive = true }
                    }
                }
            )
        }

        composable(Route.Settings.path) {
            SettingsScreen(
                onNavigateBack = { navController.popBackStack() },
                onManageProviders = { navController.navigate(Route.ProviderList.path) },
                onManageAgents = { navController.navigate(Route.AgentList.path) },
                onUsageStatistics = { navController.navigate(Route.UsageStatistics.path) },
                onDataBackup = { navController.navigate(Route.DataBackup.path) },
                onMemory = { navController.navigate(Route.Memory.path) }
            )
        }

        composable(Route.UsageStatistics.path) {
            UsageStatisticsScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Route.DataBackup.path) {
            val context = androidx.compose.ui.platform.LocalContext.current
            DataBackupScreen(
                onNavigateBack = { navController.popBackStack() },
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
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
