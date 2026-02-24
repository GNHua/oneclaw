package com.tomandy.oneclaw.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.tomandy.oneclaw.ui.drawColumnScrollbar
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.tomandy.oneclaw.data.ModelPreferences
import com.tomandy.oneclaw.llm.LlmProvider

@Composable
fun SettingsScreen(
    onNavigateToProviders: () -> Unit,
    onNavigateToPlugins: () -> Unit,
    onNavigateToSkills: () -> Unit,
    onNavigateToMemory: () -> Unit,
    onNavigateToBackup: () -> Unit,
    onNavigateToAgentProfiles: () -> Unit,
    onNavigateToGoogleAccount: () -> Unit,
    onNavigateToAppearance: () -> Unit,
    onNavigateToMessagingBridge: () -> Unit = {},
    modelPreferences: ModelPreferences,
    availableModels: List<Pair<String, LlmProvider>>,
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var isModelExpanded by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .drawColumnScrollbar(scrollState, scrollbarColor)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Group 1: Model selector
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isModelExpanded = !isModelExpanded }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Text(
                        text = "Model",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = selectedModel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (isModelExpanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.rotate(if (isModelExpanded) 180f else 0f)
                    )
                }
                AnimatedVisibility(
                    visible = isModelExpanded,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        availableModels.forEach { (model, provider) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onModelSelected(model)
                                        isModelExpanded = false
                                    }
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = model == selectedModel,
                                    onClick = {
                                        onModelSelected(model)
                                        isModelExpanded = false
                                    }
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = model,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = provider.displayName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Group 2: Configuration
        SettingsGroup {
            GroupItem(
                icon = Icons.Outlined.Key,
                title = "Providers",
                onClick = onNavigateToProviders
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            GroupItem(
                icon = Icons.Outlined.Extension,
                title = "Plugins",
                onClick = onNavigateToPlugins
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            GroupItem(
                icon = Icons.Outlined.AutoAwesome,
                title = "Skills",
                onClick = onNavigateToSkills
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            GroupItem(
                icon = Icons.Outlined.Groups,
                title = "Agent Profiles",
                onClick = onNavigateToAgentProfiles
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            GroupItem(
                icon = Icons.Outlined.Psychology,
                title = "Memory",
                onClick = onNavigateToMemory
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            GroupItem(
                icon = Icons.Outlined.SmartToy,
                title = "Messaging Bridge",
                onClick = onNavigateToMessagingBridge
            )
        }

        // Group 3: Account & System
        SettingsGroup {
            GroupItem(
                icon = Icons.Outlined.AccountCircle,
                title = "Google Account",
                onClick = onNavigateToGoogleAccount
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            GroupItem(
                icon = Icons.Outlined.CloudUpload,
                title = "Backup & Restore",
                onClick = onNavigateToBackup
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            GroupItem(
                icon = Icons.Outlined.Palette,
                title = "Appearance",
                onClick = onNavigateToAppearance
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            VoiceInputExpandableItem(modelPreferences = modelPreferences)
        }
    }
}

@Composable
private fun SettingsGroup(
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            content = content
        )
    }
}

@Composable
private fun GroupItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun VoiceInputExpandableItem(modelPreferences: ModelPreferences) {
    var expanded by remember { mutableStateOf(false) }
    var audioInputMode by remember {
        mutableStateOf(modelPreferences.getAudioInputMode())
    }

    val currentModeLabel = when (audioInputMode) {
        ModelPreferences.AudioInputMode.ALWAYS_TRANSCRIBE -> "Transcribe"
        ModelPreferences.AudioInputMode.NATIVE_WHEN_SUPPORTED -> "Native"
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "Voice Input",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = currentModeLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(if (expanded) 180f else 0f)
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            audioInputMode = ModelPreferences.AudioInputMode.ALWAYS_TRANSCRIBE
                            modelPreferences.saveAudioInputMode(audioInputMode)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = audioInputMode == ModelPreferences.AudioInputMode.ALWAYS_TRANSCRIBE,
                        onClick = {
                            audioInputMode = ModelPreferences.AudioInputMode.ALWAYS_TRANSCRIBE
                            modelPreferences.saveAudioInputMode(audioInputMode)
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            audioInputMode = ModelPreferences.AudioInputMode.NATIVE_WHEN_SUPPORTED
                            modelPreferences.saveAudioInputMode(audioInputMode)
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = audioInputMode == ModelPreferences.AudioInputMode.NATIVE_WHEN_SUPPORTED,
                        onClick = {
                            audioInputMode = ModelPreferences.AudioInputMode.NATIVE_WHEN_SUPPORTED
                            modelPreferences.saveAudioInputMode(audioInputMode)
                        }
                    )
                    Spacer(modifier = Modifier.width(12.dp))
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
}
