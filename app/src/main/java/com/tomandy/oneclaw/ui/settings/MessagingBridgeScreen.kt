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
import androidx.compose.material.icons.filled.Check
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

    var telegramDirty by remember { mutableStateOf(false) }
    var discordDirty by remember { mutableStateOf(false) }
    var webChatDirty by remember { mutableStateOf(false) }
    var slackDirty by remember { mutableStateOf(false) }
    var matrixDirty by remember { mutableStateOf(false) }
    var lineDirty by remember { mutableStateOf(false) }

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
            SetupGuide(listOf(
                "Open @BotFather in Telegram and send /newbot",
                "Follow the prompts to name your bot",
                "Copy the bot token and paste it below",
                "To find your user ID for the allow-list, message @userinfobot on Telegram -- it replies with your numeric ID"
            ))

            SecretTextField(
                value = telegramBotToken,
                onValueChange = { viewModel.updateTelegramBotToken(it); telegramDirty = true },
                label = "Bot Token"
            )

            OutlinedTextField(
                value = telegramAllowedUsers,
                onValueChange = { viewModel.updateTelegramAllowedUsers(it); telegramDirty = true },
                label = { Text("Allowed User IDs (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = settingsTextFieldShape,
                colors = settingsTextFieldColors()
            )

            Button(
                onClick = { viewModel.saveTelegramConfig(); telegramDirty = false },
                enabled = telegramDirty
            ) {
                if (!telegramDirty) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Saved")
                } else {
                    Text("Save")
                }
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
            SetupGuide(listOf(
                "Go to discord.com/developers/applications and create a New Application",
                "Go to Bot tab, click Reset Token, and copy it",
                "Under Privileged Gateway Intents, enable Message Content Intent",
                "Go to OAuth2 > URL Generator, select \"bot\" scope with \"Send Messages\" and \"Read Message History\" permissions, then open the generated URL to invite the bot to your server",
                "Enable Developer Mode in Discord settings (App Settings > Advanced), then right-click your username to Copy User ID for the allow-list"
            ))

            SecretTextField(
                value = discordBotToken,
                onValueChange = { viewModel.updateDiscordBotToken(it); discordDirty = true },
                label = "Bot Token"
            )

            OutlinedTextField(
                value = discordAllowedUsers,
                onValueChange = { viewModel.updateDiscordAllowedUsers(it); discordDirty = true },
                label = { Text("Allowed User IDs (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = settingsTextFieldShape,
                colors = settingsTextFieldColors()
            )

            Button(
                onClick = { viewModel.saveDiscordConfig(); discordDirty = false },
                enabled = discordDirty
            ) {
                if (!discordDirty) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Saved")
                } else {
                    Text("Save")
                }
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
            SetupGuide(listOf(
                "Choose a port (default 8080) -- make sure it is not in use by another app",
                "Optionally set an access token to restrict who can connect"
            ))

            OutlinedTextField(
                value = webChatPort,
                onValueChange = { viewModel.updateWebChatPort(it); webChatDirty = true },
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
                onValueChange = { viewModel.updateWebChatAccessToken(it); webChatDirty = true },
                label = "Access Token"
            )

            Button(
                onClick = { viewModel.saveWebChatConfig(); webChatDirty = false },
                enabled = webChatDirty
            ) {
                if (!webChatDirty) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Saved")
                } else {
                    Text("Save")
                }
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
            SetupGuide(listOf(
                "Go to api.slack.com/apps and create a New App (from scratch)",
                "Under OAuth & Permissions, add \"chat:write\" and \"channels:history\" bot token scopes, then install to your workspace and copy the Bot Token (xoxb-...)",
                "Under Basic Information > App-Level Tokens, generate a token with \"connections:write\" scope -- this is your App Token (xapp-...)",
                "Enable Socket Mode under Socket Mode settings",
                "Under Event Subscriptions, enable events and subscribe to \"message.channels\" bot event, then find your Slack user ID in your profile for the allow-list"
            ))

            SecretTextField(
                value = slackBotToken,
                onValueChange = { viewModel.updateSlackBotToken(it); slackDirty = true },
                label = "Bot Token"
            )

            SecretTextField(
                value = slackAppToken,
                onValueChange = { viewModel.updateSlackAppToken(it); slackDirty = true },
                label = "App Token"
            )

            OutlinedTextField(
                value = slackAllowedUsers,
                onValueChange = { viewModel.updateSlackAllowedUsers(it); slackDirty = true },
                label = { Text("Allowed User IDs (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = settingsTextFieldShape,
                colors = settingsTextFieldColors()
            )

            Button(
                onClick = { viewModel.saveSlackConfig(); slackDirty = false },
                enabled = slackDirty
            ) {
                if (!slackDirty) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Saved")
                } else {
                    Text("Save")
                }
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
            SetupGuide(listOf(
                "Register a new account on your homeserver to use as the bot (e.g. via Element), and enter the homeserver URL below (e.g. https://matrix.org)",
                "Retrieve an access token -- in Element: Settings > Help & About > Advanced > Access Token",
                "Invite the bot user to the rooms you want to bridge, and add your Matrix user ID (e.g. @user:matrix.org) to the allow-list"
            ))

            OutlinedTextField(
                value = matrixHomeserver,
                onValueChange = { viewModel.updateMatrixHomeserver(it); matrixDirty = true },
                label = { Text("Homeserver URL") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = settingsTextFieldShape,
                colors = settingsTextFieldColors()
            )

            SecretTextField(
                value = matrixAccessToken,
                onValueChange = { viewModel.updateMatrixAccessToken(it); matrixDirty = true },
                label = "Access Token"
            )

            OutlinedTextField(
                value = matrixAllowedUsers,
                onValueChange = { viewModel.updateMatrixAllowedUsers(it); matrixDirty = true },
                label = { Text("Allowed User IDs (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = settingsTextFieldShape,
                colors = settingsTextFieldColors()
            )

            Button(
                onClick = { viewModel.saveMatrixConfig(); matrixDirty = false },
                enabled = matrixDirty
            ) {
                if (!matrixDirty) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Saved")
                } else {
                    Text("Save")
                }
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
            SetupGuide(listOf(
                "Go to developers.line.biz, create a provider, then create a Messaging API channel",
                "In the channel settings, issue a Channel Access Token (long-lived) and copy it",
                "Copy the Channel Secret from Basic Settings",
                "Find user IDs by enabling webhooks and checking the \"userId\" field in received events, or use the LINE API to look up your own profile"
            ))

            SecretTextField(
                value = lineChannelAccessToken,
                onValueChange = { viewModel.updateLineChannelAccessToken(it); lineDirty = true },
                label = "Channel Access Token"
            )

            SecretTextField(
                value = lineChannelSecret,
                onValueChange = { viewModel.updateLineChannelSecret(it); lineDirty = true },
                label = "Channel Secret"
            )

            OutlinedTextField(
                value = lineAllowedUsers,
                onValueChange = { viewModel.updateLineAllowedUsers(it); lineDirty = true },
                label = { Text("Allowed User IDs (comma-separated)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = settingsTextFieldShape,
                colors = settingsTextFieldColors()
            )

            Button(
                onClick = { viewModel.saveLineConfig(); lineDirty = false },
                enabled = lineDirty
            ) {
                if (!lineDirty) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Saved")
                } else {
                    Text("Save")
                }
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
private fun SetupGuide(steps: List<String>) {
    var expanded by remember { mutableStateOf(false) }

    Column {
        Text(
            text = if (expanded) "Hide setup guide" else "Setup guide",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.clickable { expanded = !expanded }
        )

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                steps.forEachIndexed { index, step ->
                    Text(
                        text = "${index + 1}. $step",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
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
