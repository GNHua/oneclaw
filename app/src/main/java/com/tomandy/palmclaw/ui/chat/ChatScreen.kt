package com.tomandy.palmclaw.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomandy.palmclaw.PalmClawApp
import kotlinx.coroutines.launch

/**
 * Main chat screen for the PalmClaw application.
 *
 * This composable orchestrates the entire chat experience:
 * - Message list with conversation history
 * - Chat input field for user messages
 * - Loading indicators during AI processing
 * - Error handling with snackbars
 * - Empty state for new conversations
 * - Navigation to settings
 * - New conversation creation
 *
 * @param viewModel The ChatViewModel for state management
 * @param onNavigateToSettings Callback to navigate to settings screen
 * @param modifier Modifier for styling
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    app: PalmClawApp,
    onNavigateToSettings: () -> Unit,
    onNavigateToCronjobs: () -> Unit,
    onNavigateToHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val messages by viewModel.messages.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val error by viewModel.error.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    val scope = rememberCoroutineScope()
    var availableModels by remember { mutableStateOf<List<Pair<String, com.tomandy.palmclaw.llm.LlmProvider>>>(emptyList()) }
    var selectedModel by remember { mutableStateOf(app.modelPreferences.getSelectedModel() ?: "gpt-4o-mini") }
    var isModelDropdownExpanded by remember { mutableStateOf(false) }

    // Load available models and reload API keys
    LaunchedEffect(Unit) {
        // Reload API keys to ensure newly saved keys are loaded
        app.reloadApiKeys()
        availableModels = app.getAvailableModels()
        if (availableModels.isNotEmpty() && selectedModel !in availableModels.map { it.first }) {
            selectedModel = availableModels.first().first
            app.setModelAndProvider(selectedModel)
        }
    }

    // Show error in snackbar
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(
                message = it,
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Long
            )
            viewModel.clearError()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("PalmClaw") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateToHistory) {
                            Icon(Icons.Default.Menu, contentDescription = "Conversation history")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.newConversation() }) {
                            Icon(Icons.Default.Add, contentDescription = "New conversation")
                        }
                        IconButton(onClick = onNavigateToCronjobs) {
                            Icon(Icons.Default.DateRange, contentDescription = "Scheduled tasks")
                        }
                        IconButton(onClick = onNavigateToSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
                // Model selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Model:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
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
                                        selectedModel = model
                                        app.setModelAndProvider(model)
                                        isModelDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Message list
            Box(modifier = Modifier.weight(1f)) {
                if (messages.isEmpty() && !isProcessing) {
                    // Empty state
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Start a conversation",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Ask me anything!",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    MessageList(
                        messages = messages,
                        isProcessing = isProcessing,
                        listState = listState
                    )
                }
            }

            // Input field
            ChatInput(
                value = inputText,
                onValueChange = { inputText = it },
                onSend = {
                    viewModel.sendMessage(inputText)
                    inputText = ""
                },
                enabled = !isProcessing
            )
        }
    }
}
