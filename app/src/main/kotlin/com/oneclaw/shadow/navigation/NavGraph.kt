package com.oneclaw.shadow.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.oneclaw.shadow.core.repository.SettingsRepository
import com.oneclaw.shadow.feature.provider.ProviderDetailScreen
import com.oneclaw.shadow.feature.provider.ProviderListScreen
import com.oneclaw.shadow.feature.provider.SetupScreen
import com.oneclaw.shadow.feature.provider.SettingsScreen
import org.koin.compose.koinInject

@Composable
fun AppNavGraph(
    navController: NavHostController,
    modifier: Modifier = Modifier
) {
    val settingsRepository: SettingsRepository = koinInject()

    // First-launch detection: read setup flag and navigate accordingly
    LaunchedEffect(Unit) {
        val hasCompletedSetup = settingsRepository.getBoolean("has_completed_setup", false)
        if (!hasCompletedSetup) {
            navController.navigate(Route.Setup.path) {
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
            PlaceholderScreen("Chat (New)")
        }

        composable(Route.ChatSession.PATH) { backStackEntry ->
            val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
            PlaceholderScreen("Chat: $sessionId")
        }

        composable(Route.AgentList.path) {
            PlaceholderScreen("Agent List")
        }

        composable(Route.AgentDetail.PATH) { backStackEntry ->
            val agentId = backStackEntry.arguments?.getString("agentId") ?: ""
            PlaceholderScreen("Agent Detail: $agentId")
        }

        composable(Route.AgentCreate.path) {
            PlaceholderScreen("Create Agent")
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
                onManageProviders = { navController.navigate(Route.ProviderList.path) }
            )
        }
    }
}

@Composable
private fun PlaceholderScreen(title: String) {
    androidx.compose.foundation.layout.Box(
        modifier = Modifier.then(Modifier),
        contentAlignment = androidx.compose.ui.Alignment.Center
    ) {
        androidx.compose.material3.Text(text = title)
    }
}
