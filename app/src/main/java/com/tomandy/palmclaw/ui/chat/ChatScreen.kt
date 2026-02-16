package com.tomandy.palmclaw.ui.chat

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalContext
import com.tomandy.palmclaw.audio.AudioInputController
import com.tomandy.palmclaw.audio.AudioState
import com.tomandy.palmclaw.audio.AndroidSttProvider
import com.tomandy.palmclaw.llm.LlmClientProvider
import com.tomandy.palmclaw.notification.ChatNotificationHelper
import com.tomandy.palmclaw.notification.ChatScreenTracker
import com.tomandy.palmclaw.util.ImageStorageHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

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

    // Audio input
    val audioInputController: AudioInputController = koinInject()
    val sttProvider: AndroidSttProvider = koinInject()
    val llmClientProvider: LlmClientProvider = koinInject()
    val audioState by audioInputController.state.collectAsState()

    // Track whether this screen is visible and dismiss any pending notification.
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
    val attachedImages = remember { mutableStateListOf<String>() }
    val attachedAudios = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // RECORD_AUDIO permission launcher
    var pendingMicAction by remember { mutableStateOf(false) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted && pendingMicAction) {
            pendingMicAction = false
            // Trigger the mic action now that permission is granted
            handleMicTap(
                audioInputController = audioInputController,
                sttProvider = sttProvider,
                llmClientProvider = llmClientProvider,
                conversationId = currentConversationId,
                audioState = audioState,
                scope = scope,
                onTextResult = { text -> inputText = (inputText + " " + text).trim() },
                onAudioResult = { path -> attachedAudios.add(path) },
                onError = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
            )
        } else if (!granted) {
            scope.launch {
                snackbarHostState.showSnackbar("Microphone permission is required for voice input")
            }
        }
    }

    // Image picker launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)
    ) { uris ->
        scope.launch {
            uris.forEach { uri ->
                val path = withContext(Dispatchers.IO) {
                    ImageStorageHelper.copyImageToStorage(context, uri, currentConversationId)
                }
                if (path != null) {
                    attachedImages.add(path)
                }
            }
        }
    }

    // Maintain scroll position when keyboard opens/closes
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
                    viewModel.sendMessage(inputText, attachedImages.toList(), attachedAudios.toList())
                    inputText = ""
                    attachedImages.clear()
                    attachedAudios.clear()
                },
                onStop = { viewModel.cancelRequest() },
                onAttachImage = {
                    // Check clipboard for image first
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clipUri = clipboard.primaryClip?.let { clip ->
                        if (clip.itemCount > 0) {
                            val mime = clipboard.primaryClipDescription?.getMimeType(0)
                            if (mime?.startsWith("image/") == true) clip.getItemAt(0).uri else null
                        } else null
                    }

                    if (clipUri != null) {
                        scope.launch {
                            val path = withContext(Dispatchers.IO) {
                                ImageStorageHelper.copyImageToStorage(context, clipUri, currentConversationId)
                            }
                            if (path != null) {
                                attachedImages.add(path)
                            }
                        }
                    } else {
                        imagePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                },
                onMicTap = {
                    // Check permission first
                    val hasPermission = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED

                    if (!hasPermission) {
                        pendingMicAction = true
                        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        handleMicTap(
                            audioInputController = audioInputController,
                            sttProvider = sttProvider,
                            llmClientProvider = llmClientProvider,
                            conversationId = currentConversationId,
                            audioState = audioState,
                            scope = scope,
                            onTextResult = { text -> inputText = (inputText + " " + text).trim() },
                            onAudioResult = { path -> attachedAudios.add(path) },
                            onError = { msg -> scope.launch { snackbarHostState.showSnackbar(msg) } }
                        )
                    }
                },
                isProcessing = isProcessing,
                isRecording = audioState == AudioState.RECORDING || audioState == AudioState.LISTENING,
                attachedImages = attachedImages,
                attachedAudios = attachedAudios,
                onRemoveImage = { index -> attachedImages.removeAt(index) },
                onRemoveAudio = { index ->
                    attachedAudios.removeAt(index)
                    audioInputController.cancelRecording()
                }
            )
        }
    }
}

private fun handleMicTap(
    audioInputController: AudioInputController,
    sttProvider: AndroidSttProvider,
    llmClientProvider: LlmClientProvider,
    conversationId: String,
    audioState: AudioState,
    scope: kotlinx.coroutines.CoroutineScope,
    onTextResult: (String) -> Unit,
    onAudioResult: (String) -> Unit,
    onError: (String) -> Unit
) {
    when (audioState) {
        AudioState.IDLE -> {
            val currentProvider = llmClientProvider.selectedProvider.value
            if (audioInputController.willSendNativeAudio(currentProvider)) {
                // Native audio mode: record to file
                audioInputController.startRecording(conversationId)
            } else {
                // STT mode: live recognition
                audioInputController.setListening()
                scope.launch {
                    val result = sttProvider.recognizeLive()
                    audioInputController.setIdle()
                    result.fold(
                        onSuccess = { text -> onTextResult(text) },
                        onFailure = { e -> onError(e.message ?: "Speech recognition failed") }
                    )
                }
            }
        }
        AudioState.RECORDING -> {
            // Stop recording and attach the audio
            val path = audioInputController.stopRecording()
            if (path != null) {
                onAudioResult(path)
            }
        }
        AudioState.LISTENING -> {
            // Can't stop live STT mid-recognition; it will finish on its own
        }
    }
}
