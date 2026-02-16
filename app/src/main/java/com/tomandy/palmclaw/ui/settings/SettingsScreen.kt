package com.tomandy.palmclaw.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomandy.palmclaw.data.ModelPreferences
import com.tomandy.palmclaw.llm.LlmProvider
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    onNavigateToProviders: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onNavigateToSkills: () -> Unit,
    onNavigateToMemory: () -> Unit,
    onNavigateToBackup: () -> Unit,
    onNavigateToAgentProfiles: () -> Unit,
    modelPreferences: ModelPreferences,
    availableModels: List<Pair<String, LlmProvider>>,
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var maxIterations by remember {
        mutableIntStateOf(modelPreferences.getMaxIterations())
    }
    var isModelDropdownExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Model selector
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
                Text(
                    text = "Model",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Select the LLM model to use for chat",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Box {
                    TextButton(
                        onClick = { isModelDropdownExpanded = true },
                        enabled = availableModels.isNotEmpty()
                    ) {
                        Text(selectedModel)
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = "Select model"
                        )
                    }
                    DropdownMenu(
                        expanded = isModelDropdownExpanded,
                        onDismissRequest = { isModelDropdownExpanded = false }
                    ) {
                        availableModels.forEach { (model, provider) ->
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(model)
                                        Text(
                                            text = provider.displayName,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                },
                                onClick = {
                                    onModelSelected(model)
                                    isModelDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

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
        SettingsMenuItem(
            title = "Skills",
            subtitle = "Manage skill slash commands",
            onClick = onNavigateToSkills
        )
        SettingsMenuItem(
            title = "Memory",
            subtitle = "View agent memories across conversations",
            onClick = onNavigateToMemory
        )
        SettingsMenuItem(
            title = "Backup & Restore",
            subtitle = "Export or import app data",
            onClick = onNavigateToBackup
        )
        SettingsMenuItem(
            title = "Agent Profiles",
            subtitle = "Create and manage agent personas",
            onClick = onNavigateToAgentProfiles
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Audio Input Mode
        AudioInputModeCard(modelPreferences = modelPreferences)

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
private fun AudioInputModeCard(modelPreferences: ModelPreferences) {
    var audioInputMode by remember {
        mutableStateOf(modelPreferences.getAudioInputMode())
    }

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
            Text(
                text = "Voice Input",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "How to handle microphone input",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        audioInputMode = ModelPreferences.AudioInputMode.ALWAYS_TRANSCRIBE
                        modelPreferences.saveAudioInputMode(audioInputMode)
                    }
                    .padding(vertical = 4.dp)
            ) {
                RadioButton(
                    selected = audioInputMode == ModelPreferences.AudioInputMode.ALWAYS_TRANSCRIBE,
                    onClick = {
                        audioInputMode = ModelPreferences.AudioInputMode.ALWAYS_TRANSCRIBE
                        modelPreferences.saveAudioInputMode(audioInputMode)
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("Always transcribe", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Convert speech to text (works with all providers)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        audioInputMode = ModelPreferences.AudioInputMode.NATIVE_WHEN_SUPPORTED
                        modelPreferences.saveAudioInputMode(audioInputMode)
                    }
                    .padding(vertical = 4.dp)
            ) {
                RadioButton(
                    selected = audioInputMode == ModelPreferences.AudioInputMode.NATIVE_WHEN_SUPPORTED,
                    onClick = {
                        audioInputMode = ModelPreferences.AudioInputMode.NATIVE_WHEN_SUPPORTED
                        modelPreferences.saveAudioInputMode(audioInputMode)
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text("Send audio when supported", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Send raw audio to OpenAI/Gemini (transcribe for others)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
