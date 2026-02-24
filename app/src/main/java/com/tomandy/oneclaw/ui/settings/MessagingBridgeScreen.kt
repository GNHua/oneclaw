package com.tomandy.oneclaw.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tomandy.oneclaw.bridge.ChannelType
import com.tomandy.oneclaw.ui.drawColumnScrollbar
import com.tomandy.oneclaw.ui.theme.settingsTextFieldColors
import com.tomandy.oneclaw.ui.theme.settingsTextFieldShape

@Composable
fun MessagingBridgeScreen(
    viewModel: MessagingBridgeViewModel,
    modifier: Modifier = Modifier
) {
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

    val slackEnabled by viewModel.slackEnabled.collectAsState()
    val slackBotToken by viewModel.slackBotToken.collectAsState()
    val slackAppToken by viewModel.slackAppToken.collectAsState()
    val slackAllowedUsers by viewModel.slackAllowedUsers.collectAsState()

    val matrixEnabled by viewModel.matrixEnabled.collectAsState()
    val matrixHomeserver by viewModel.matrixHomeserver.collectAsState()
    val matrixAccessToken by viewModel.matrixAccessToken.collectAsState()
    val matrixAllowedUsers by viewModel.matrixAllowedUsers.collectAsState()

    val lineEnabled by viewModel.lineEnabled.collectAsState()
    val lineChannelAccessToken by viewModel.lineChannelAccessToken.collectAsState()
    val lineChannelSecret by viewModel.lineChannelSecret.collectAsState()
    val lineAllowedUsers by viewModel.lineAllowedUsers.collectAsState()

    var expandedChannel by remember { mutableStateOf<ChannelType?>(null) }

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
            .imePadding()
            .drawColumnScrollbar(scrollState, scrollbarColor)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
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

        // Telegram
        ChannelGroup(
            title = "Telegram",
            isEnabled = telegramEnabled,
            onEnabledChanged = { viewModel.setTelegramEnabled(it) },
            isExpanded = expandedChannel == ChannelType.TELEGRAM,
            onToggle = {
                expandedChannel = if (expandedChannel == ChannelType.TELEGRAM) null else ChannelType.TELEGRAM
            }
        ) {
            SecretTextField(
                value = telegramBotToken,
                onValueChange = { viewModel.updateTelegramBotToken(it) },
                label = "Bot Token"
            )

            OutlinedTextField(
                value = telegramAllowedUsers,
                onValueChange = { viewModel.updateTelegramAllowedUsers(it) },
                label = { Text("Allowed User IDs (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = settingsTextFieldShape,
                colors = settingsTextFieldColors()
            )

            Button(
                onClick = { viewModel.saveTelegramConfig() },
                enabled = telegramBotToken.isNotBlank()
            ) {
                Text("Save")
            }
        }

        // Discord
        ChannelGroup(
            title = "Discord",
            isEnabled = discordEnabled,
            onEnabledChanged = { viewModel.setDiscordEnabled(it) },
            isExpanded = expandedChannel == ChannelType.DISCORD,
            onToggle = {
                expandedChannel = if (expandedChannel == ChannelType.DISCORD) null else ChannelType.DISCORD
            }
        ) {
            SecretTextField(
                value = discordBotToken,
                onValueChange = { viewModel.updateDiscordBotToken(it) },
                label = "Bot Token"
            )

            OutlinedTextField(
                value = discordAllowedUsers,
                onValueChange = { viewModel.updateDiscordAllowedUsers(it) },
                label = { Text("Allowed User IDs (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = settingsTextFieldShape,
                colors = settingsTextFieldColors()
            )

            Button(
                onClick = { viewModel.saveDiscordConfig() },
                enabled = discordBotToken.isNotBlank()
            ) {
                Text("Save")
            }
        }

        // WebChat
        ChannelGroup(
            title = "WebChat",
            isEnabled = webChatEnabled,
            onEnabledChanged = { viewModel.setWebChatEnabled(it) },
            isExpanded = expandedChannel == ChannelType.WEBCHAT,
            onToggle = {
                expandedChannel = if (expandedChannel == ChannelType.WEBCHAT) null else ChannelType.WEBCHAT
            }
        ) {
            OutlinedTextField(
                value = webChatPort,
                onValueChange = { viewModel.updateWebChatPort(it) },
                label = { Text("Port") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = settingsTextFieldShape,
                colors = settingsTextFieldColors()
            )

            SecretTextField(
                value = webChatAccessToken,
                onValueChange = { viewModel.updateWebChatAccessToken(it) },
                label = "Access Token"
            )

            Button(
                onClick = { viewModel.saveWebChatConfig() },
                enabled = true
            ) {
                Text("Save")
            }
        }

        // Slack
        ChannelGroup(
            title = "Slack",
            isEnabled = slackEnabled,
            onEnabledChanged = { viewModel.setSlackEnabled(it) },
            isExpanded = expandedChannel == ChannelType.SLACK,
            onToggle = {
                expandedChannel = if (expandedChannel == ChannelType.SLACK) null else ChannelType.SLACK
            }
        ) {
            SecretTextField(
                value = slackBotToken,
                onValueChange = { viewModel.updateSlackBotToken(it) },
                label = "Bot Token"
            )

            SecretTextField(
                value = slackAppToken,
                onValueChange = { viewModel.updateSlackAppToken(it) },
                label = "App Token"
            )

            OutlinedTextField(
                value = slackAllowedUsers,
                onValueChange = { viewModel.updateSlackAllowedUsers(it) },
                label = { Text("Allowed User IDs (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = settingsTextFieldShape,
                colors = settingsTextFieldColors()
            )

            Button(
                onClick = { viewModel.saveSlackConfig() },
                enabled = slackBotToken.isNotBlank()
            ) {
                Text("Save")
            }
        }

        // Matrix
        ChannelGroup(
            title = "Matrix",
            isEnabled = matrixEnabled,
            onEnabledChanged = { viewModel.setMatrixEnabled(it) },
            isExpanded = expandedChannel == ChannelType.MATRIX,
            onToggle = {
                expandedChannel = if (expandedChannel == ChannelType.MATRIX) null else ChannelType.MATRIX
            }
        ) {
            OutlinedTextField(
                value = matrixHomeserver,
                onValueChange = { viewModel.updateMatrixHomeserver(it) },
                label = { Text("Homeserver URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = settingsTextFieldShape,
                colors = settingsTextFieldColors()
            )

            SecretTextField(
                value = matrixAccessToken,
                onValueChange = { viewModel.updateMatrixAccessToken(it) },
                label = "Access Token"
            )

            OutlinedTextField(
                value = matrixAllowedUsers,
                onValueChange = { viewModel.updateMatrixAllowedUsers(it) },
                label = { Text("Allowed User IDs (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = settingsTextFieldShape,
                colors = settingsTextFieldColors()
            )

            Button(
                onClick = { viewModel.saveMatrixConfig() },
                enabled = matrixHomeserver.isNotBlank() && matrixAccessToken.isNotBlank()
            ) {
                Text("Save")
            }
        }

        // LINE
        ChannelGroup(
            title = "LINE",
            isEnabled = lineEnabled,
            onEnabledChanged = { viewModel.setLineEnabled(it) },
            isExpanded = expandedChannel == ChannelType.LINE,
            onToggle = {
                expandedChannel = if (expandedChannel == ChannelType.LINE) null else ChannelType.LINE
            }
        ) {
            SecretTextField(
                value = lineChannelAccessToken,
                onValueChange = { viewModel.updateLineChannelAccessToken(it) },
                label = "Channel Access Token"
            )

            SecretTextField(
                value = lineChannelSecret,
                onValueChange = { viewModel.updateLineChannelSecret(it) },
                label = "Channel Secret"
            )

            OutlinedTextField(
                value = lineAllowedUsers,
                onValueChange = { viewModel.updateLineAllowedUsers(it) },
                label = { Text("Allowed User IDs (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = settingsTextFieldShape,
                colors = settingsTextFieldColors()
            )

            Button(
                onClick = { viewModel.saveLineConfig() },
                enabled = lineChannelAccessToken.isNotBlank()
            ) {
                Text("Save")
            }
        }
    }
}

@Composable
private fun ChannelGroup(
    title: String,
    isEnabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isEnabled || isExpanded) 1f else 0.5f)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = isEnabled,
                    onCheckedChange = onEnabledChanged,
                    modifier = Modifier.scale(0.8f)
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(if (isExpanded) 180f else 0f)
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        content = content
                    )
                }
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
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (visible) "Hide" else "Show"
                )
            }
        },
        textStyle = MaterialTheme.typography.bodyMedium,
        shape = settingsTextFieldShape,
        colors = settingsTextFieldColors()
    )
}
