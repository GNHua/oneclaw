package com.oneclaw.shadow.feature.bridge

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.oneclaw.shadow.bridge.service.MessagingBridgeService
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BridgeSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: BridgeSettingsViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Messaging Bridge") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Master bridge toggle
            BridgeSwitchRow(
                label = "Enable Messaging Bridge",
                checked = state.bridgeEnabled,
                onCheckedChange = { enabled ->
                    viewModel.toggleBridge(enabled)
                    if (enabled) {
                        MessagingBridgeService.start(context)
                    } else {
                        MessagingBridgeService.stop(context)
                    }
                }
            )

            if (state.serviceRunning) {
                Text(
                    text = "Service is running",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                )
            }

            BridgeSwitchRow(
                label = "Keep CPU awake (Wake Lock)",
                checked = state.wakeLockEnabled,
                onCheckedChange = { viewModel.toggleWakeLock(it) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Telegram
            ChannelSection(title = "Telegram") {
                BridgeSwitchRow(
                    label = "Enable Telegram",
                    checked = state.telegramEnabled,
                    onCheckedChange = { viewModel.toggleTelegram(it) }
                )
                if (state.telegramEnabled) {
                    BridgeTextField(
                        label = "Bot Token",
                        value = state.telegramBotToken,
                        onValueChange = { viewModel.updateTelegramBotToken(it) }
                    )
                    BridgeTextField(
                        label = "Allowed User IDs (comma-separated)",
                        value = state.telegramAllowedUserIds,
                        onValueChange = { viewModel.updateTelegramAllowedUserIds(it) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Discord
            ChannelSection(title = "Discord") {
                BridgeSwitchRow(
                    label = "Enable Discord",
                    checked = state.discordEnabled,
                    onCheckedChange = { viewModel.toggleDiscord(it) }
                )
                if (state.discordEnabled) {
                    BridgeTextField(
                        label = "Bot Token",
                        value = state.discordBotToken,
                        onValueChange = { viewModel.updateDiscordBotToken(it) }
                    )
                    BridgeTextField(
                        label = "Allowed User IDs (comma-separated)",
                        value = state.discordAllowedUserIds,
                        onValueChange = { viewModel.updateDiscordAllowedUserIds(it) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Slack
            ChannelSection(title = "Slack") {
                BridgeSwitchRow(
                    label = "Enable Slack",
                    checked = state.slackEnabled,
                    onCheckedChange = { viewModel.toggleSlack(it) }
                )
                if (state.slackEnabled) {
                    BridgeTextField(
                        label = "Bot Token (xoxb-...)",
                        value = state.slackBotToken,
                        onValueChange = { viewModel.updateSlackBotToken(it) }
                    )
                    BridgeTextField(
                        label = "App Token (xapp-...)",
                        value = state.slackAppToken,
                        onValueChange = { viewModel.updateSlackAppToken(it) }
                    )
                    BridgeTextField(
                        label = "Allowed User IDs (comma-separated)",
                        value = state.slackAllowedUserIds,
                        onValueChange = { viewModel.updateSlackAllowedUserIds(it) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // Matrix
            ChannelSection(title = "Matrix") {
                BridgeSwitchRow(
                    label = "Enable Matrix",
                    checked = state.matrixEnabled,
                    onCheckedChange = { viewModel.toggleMatrix(it) }
                )
                if (state.matrixEnabled) {
                    BridgeTextField(
                        label = "Homeserver URL",
                        value = state.matrixHomeserver,
                        onValueChange = { viewModel.updateMatrixHomeserver(it) }
                    )
                    BridgeTextField(
                        label = "Access Token",
                        value = state.matrixAccessToken,
                        onValueChange = { viewModel.updateMatrixAccessToken(it) }
                    )
                    BridgeTextField(
                        label = "Allowed User IDs (comma-separated)",
                        value = state.matrixAllowedUserIds,
                        onValueChange = { viewModel.updateMatrixAllowedUserIds(it) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // LINE
            ChannelSection(title = "LINE") {
                BridgeSwitchRow(
                    label = "Enable LINE",
                    checked = state.lineEnabled,
                    onCheckedChange = { viewModel.toggleLine(it) }
                )
                if (state.lineEnabled) {
                    BridgeTextField(
                        label = "Channel Access Token",
                        value = state.lineChannelAccessToken,
                        onValueChange = { viewModel.updateLineChannelAccessToken(it) }
                    )
                    BridgeTextField(
                        label = "Channel Secret",
                        value = state.lineChannelSecret,
                        onValueChange = { viewModel.updateLineChannelSecret(it) }
                    )
                    BridgeTextField(
                        label = "Webhook Port",
                        value = state.lineWebhookPort.toString(),
                        onValueChange = { viewModel.updateLineWebhookPort(it.toIntOrNull() ?: state.lineWebhookPort) },
                        keyboardType = KeyboardType.Number
                    )
                    BridgeTextField(
                        label = "Allowed User IDs (comma-separated)",
                        value = state.lineAllowedUserIds,
                        onValueChange = { viewModel.updateLineAllowedUserIds(it) }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // WebChat
            ChannelSection(title = "Web Chat") {
                BridgeSwitchRow(
                    label = "Enable Web Chat",
                    checked = state.webChatEnabled,
                    onCheckedChange = { viewModel.toggleWebChat(it) }
                )
                if (state.webChatEnabled) {
                    BridgeTextField(
                        label = "Access Token (optional)",
                        value = state.webChatAccessToken,
                        onValueChange = { viewModel.updateWebChatAccessToken(it) }
                    )
                    BridgeTextField(
                        label = "Port",
                        value = state.webChatPort.toString(),
                        onValueChange = { viewModel.updateWebChatPort(it.toIntOrNull() ?: state.webChatPort) },
                        keyboardType = KeyboardType.Number
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun ChannelSection(
    title: String,
    content: @Composable () -> Unit
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    content()
}

@Composable
private fun BridgeSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun BridgeTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType)
    )
}
