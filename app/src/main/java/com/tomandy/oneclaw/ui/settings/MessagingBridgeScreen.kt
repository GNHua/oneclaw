package com.tomandy.oneclaw.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tomandy.oneclaw.bridge.BridgeStateTracker
import com.tomandy.oneclaw.bridge.ChannelType
import com.tomandy.oneclaw.ui.drawColumnScrollbar

@Composable
fun MessagingBridgeScreen(
    viewModel: MessagingBridgeViewModel,
    modifier: Modifier = Modifier
) {
    val serviceRunning by viewModel.serviceRunning.collectAsState()
    val channelStates by viewModel.channelStates.collectAsState()
    val saveStatus by viewModel.saveStatus.collectAsState()

    val telegramEnabled by viewModel.telegramEnabled.collectAsState()
    val telegramBotToken by viewModel.telegramBotToken.collectAsState()
    val telegramAllowedUsers by viewModel.telegramAllowedUsers.collectAsState()

    val discordEnabled by viewModel.discordEnabled.collectAsState()
    val discordBotToken by viewModel.discordBotToken.collectAsState()
    val discordAllowedUsers by viewModel.discordAllowedUsers.collectAsState()

    val webChatEnabled by viewModel.webChatEnabled.collectAsState()
    val webChatPort by viewModel.webChatPort.collectAsState()
    val webChatAccessToken by viewModel.webChatAccessToken.collectAsState()

    LaunchedEffect(saveStatus) {
        if (saveStatus != null) {
            kotlinx.coroutines.delay(2000)
            viewModel.clearSaveStatus()
        }
    }

    val scrollState = rememberScrollState()
    val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .drawColumnScrollbar(scrollState, scrollbarColor)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Status banner
        if (serviceRunning) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Bridge active",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.stopBridge() }) {
                        Text("Stop")
                    }
                }
            }
        }

        // Save status
        saveStatus?.let { status ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.tertiaryContainer
            ) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(8.dp)
                )
            }
        }

        // Telegram Section
        ChannelSection(
            title = "Telegram",
            enabled = telegramEnabled,
            onEnabledChanged = { viewModel.setTelegramEnabled(it) },
            channelState = channelStates[ChannelType.TELEGRAM]
        ) {
            SecretTextField(
                value = telegramBotToken,
                onValueChange = { viewModel.updateTelegramBotToken(it) },
                label = "Bot Token"
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = telegramAllowedUsers,
                onValueChange = { viewModel.updateTelegramAllowedUsers(it) },
                label = { Text("Allowed User IDs (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.saveTelegramConfig() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Telegram Config")
            }
        }

        // Discord Section
        ChannelSection(
            title = "Discord",
            enabled = discordEnabled,
            onEnabledChanged = { viewModel.setDiscordEnabled(it) },
            channelState = channelStates[ChannelType.DISCORD]
        ) {
            SecretTextField(
                value = discordBotToken,
                onValueChange = { viewModel.updateDiscordBotToken(it) },
                label = "Bot Token"
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = discordAllowedUsers,
                onValueChange = { viewModel.updateDiscordAllowedUsers(it) },
                label = { Text("Allowed User IDs (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.saveDiscordConfig() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save Discord Config")
            }
        }

        // WebChat Section
        ChannelSection(
            title = "WebChat",
            enabled = webChatEnabled,
            onEnabledChanged = { viewModel.setWebChatEnabled(it) },
            channelState = channelStates[ChannelType.WEBCHAT]
        ) {
            OutlinedTextField(
                value = webChatPort,
                onValueChange = { viewModel.updateWebChatPort(it) },
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            SecretTextField(
                value = webChatAccessToken,
                onValueChange = { viewModel.updateWebChatAccessToken(it) },
                label = "Access Token"
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { viewModel.saveWebChatConfig() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Save WebChat Config")
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ChannelSection(
    title: String,
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    channelState: BridgeStateTracker.ChannelState?,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChanged
                )
            }

            // Status indicator
            if (channelState != null) {
                Spacer(Modifier.height(4.dp))
                val statusText = when {
                    channelState.error != null -> "Error: ${channelState.error}"
                    channelState.isRunning -> "Connected (${channelState.messageCount} messages)"
                    else -> "Disconnected"
                }
                val statusColor = when {
                    channelState.error != null -> MaterialTheme.colorScheme.error
                    channelState.isRunning -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = statusColor
                )
            }

            if (enabled) {
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}

@Composable
private fun SecretTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    var visible by remember { mutableStateOf(false) }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { visible = !visible }) {
                Text(if (visible) "Hide" else "Show")
            }
        },
        textStyle = MaterialTheme.typography.bodyMedium
    )
}
