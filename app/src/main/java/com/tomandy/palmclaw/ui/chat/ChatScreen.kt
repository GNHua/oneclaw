package com.tomandy.palmclaw.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalContext
import com.tomandy.palmclaw.notification.ChatNotificationHelper
import com.tomandy.palmclaw.notification.ChatScreenTracker

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
    onNavigateToSettings: () -> Unit,
    onNavigateToCronjobs: () -> Unit,
    onNavigateToHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val messages by viewModel.messages.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val error by viewModel.error.collectAsState()
    val currentConversationId by viewModel.conversationId.collectAsState()

    // Track whether this screen is visible and dismiss any pending notification.
    // Uses lifecycle observer so that going to home screen or locking the device
    // clears the tracker, allowing notifications to fire.
    val lifecycleOwner = LocalLifecycleOwner.current
    val appContext = LocalContext.current.applicationContext
    DisposableEffect(currentConversationId, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    ChatScreenTracker.activeConversationId = currentConversationId
                    ChatNotificationHelper.dismiss(appContext, currentConversationId)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    ChatScreenTracker.activeConversationId = null
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        // Set immediately if already resumed
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            ChatScreenTracker.activeConversationId = currentConversationId
            ChatNotificationHelper.dismiss(appContext, currentConversationId)
        }
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            ChatScreenTracker.activeConversationId = null
        }
    }

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Maintain scroll position when keyboard opens/closes by scrolling the list
    // by exactly the same pixel delta as the keyboard height change.
    val imeHeight = WindowInsets.ime.getBottom(LocalDensity.current)
    var previousImeHeight by remember { mutableStateOf(0) }
    LaunchedEffect(imeHeight) {
        val delta = imeHeight - previousImeHeight
        previousImeHeight = imeHeight
        if (delta != 0) {
            listState.scrollBy(delta.toFloat())
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
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
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
                onStop = { viewModel.cancelRequest() },
                isProcessing = isProcessing
            )
        }
    }
}
