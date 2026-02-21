package com.tomandy.oneclaw.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.tomandy.oneclaw.ui.drawColumnScrollbar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.tomandy.oneclaw.data.ModelPreferences
import com.tomandy.oneclaw.llm.LlmProvider
import com.tomandy.oneclaw.ui.theme.Dimens

@Composable
fun SettingsScreen(
    onNavigateToHistory: () -> Unit,
    onNavigateToProviders: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onNavigateToSkills: () -> Unit,
    onNavigateToMemory: () -> Unit,
    onNavigateToBackup: () -> Unit,
    onNavigateToAgentProfiles: () -> Unit,
    onNavigateToGoogleAccount: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    modelPreferences: ModelPreferences,
    availableModels: List<Pair<String, LlmProvider>>,
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isModelDropdownExpanded by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .drawColumnScrollbar(scrollState, scrollbarColor)
            .verticalScroll(scrollState)
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.CardSpacing)
    ) {
        // Model selector
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = Dimens.CardElevation)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dimens.CardInnerPadding)
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
            icon = Icons.Outlined.History,
            title = "Conversation History",
            subtitle = "Browse and manage past conversations",
            onClick = onNavigateToHistory
        )
        SettingsMenuItem(
            icon = Icons.Outlined.Key,
            title = "Providers",
            subtitle = "Manage API keys and LLM providers",
            onClick = onNavigateToProviders
        )
        SettingsMenuItem(
            icon = Icons.Outlined.Extension,
            title = "Plugins",
            subtitle = "Enable or disable plugins",
            onClick = onNavigateToPlugins
        )
        SettingsMenuItem(
            icon = Icons.Outlined.AutoAwesome,
            title = "Skills",
            subtitle = "Manage skill slash commands",
            onClick = onNavigateToSkills
        )
        SettingsMenuItem(
            icon = Icons.Outlined.Psychology,
            title = "Memory",
            subtitle = "View agent memories across conversations",
            onClick = onNavigateToMemory
        )
        SettingsMenuItem(
            icon = Icons.Outlined.CloudUpload,
            title = "Backup & Restore",
            subtitle = "Export or import app data",
            onClick = onNavigateToBackup
        )
        SettingsMenuItem(
            icon = Icons.Outlined.AccountCircle,
            title = "Google Account",
            subtitle = "Connect Google Workspace plugins",
            onClick = onNavigateToGoogleAccount
        )
        SettingsMenuItem(
            icon = Icons.Outlined.Groups,
            title = "Agent Profiles",
            subtitle = "Create and manage agent personas",
            onClick = onNavigateToAgentProfiles
        )
        SettingsMenuItem(
            icon = Icons.Outlined.Palette,
            title = "Appearance",
            subtitle = "Theme and display settings",
            onClick = onNavigateToAppearance
        )

        // Audio Input Mode
        AudioInputModeCard(modelPreferences = modelPreferences)

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
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = Dimens.CardElevation)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.CardInnerPadding)
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
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = Dimens.CardElevation)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(Dimens.CardInnerPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
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
