package com.tomandy.palmclaw.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomandy.palmclaw.data.ModelPreferences
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onNavigateToProviders: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    modelPreferences: ModelPreferences,
    modifier: Modifier = Modifier
) {
    var maxIterations by remember {
        mutableIntStateOf(modelPreferences.getMaxIterations())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsMenuItem(
            title = "Providers",
            subtitle = "Manage API keys and LLM providers",
            onClick = onNavigateToProviders
        )
        SettingsMenuItem(
            title = "Plugins",
            subtitle = "Enable or disable plugins",
            onClick = onNavigateToPlugins
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Max Iterations setting
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Max Iterations",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Maximum ReAct loop iterations per request",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "$maxIterations",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Slider(
                    value = maxIterations.toFloat(),
                    onValueChange = {
                        maxIterations = it.roundToInt()
                    },
                    onValueChangeFinished = {
                        modelPreferences.saveMaxIterations(maxIterations)
                    },
                    valueRange = 1f..500f,
                    steps = 0
                )
            }
        }
    }
}

@Composable
private fun SettingsMenuItem(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
