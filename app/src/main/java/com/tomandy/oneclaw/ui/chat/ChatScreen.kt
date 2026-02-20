package com.tomandy.oneclaw.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.material.icons.filled.ArrowDropDown
import com.tomandy.oneclaw.audio.AudioInputController
import com.tomandy.oneclaw.audio.AudioState
import com.tomandy.oneclaw.audio.AndroidSttProvider
import com.tomandy.oneclaw.devicecontrol.DeviceControlManager
import com.tomandy.oneclaw.notificationmedia.NotificationMediaServiceManager
import com.tomandy.oneclaw.llm.LlmClientProvider
import com.tomandy.oneclaw.navigation.NavigationState
import com.tomandy.oneclaw.notification.ChatNotificationHelper
import com.tomandy.oneclaw.notification.ChatScreenTracker
import com.tomandy.oneclaw.service.ChatExecutionTracker
import com.tomandy.oneclaw.util.DocumentStorageHelper
import com.tomandy.oneclaw.util.ImageStorageHelper
import com.tomandy.oneclaw.util.VideoStorageHelper
import android.net.Uri
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
    val selectedProvider by llmClientProvider.selectedProvider.collectAsState()

    // Track whether this screen is visible and dismiss any pending notification.
    val lifecycleOwner = LocalLifecycleOwner.current
    val appContext = LocalContext.current.applicationContext
    DisposableEffect(currentConversationId, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    ChatScreenTracker.activeConversationId = currentConversationId
                    ChatNotificationHelper.dismiss(appContext, currentConversationId)
                    viewModel.refreshActiveAgent()
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

    // Agent profile selection
    val agentProfiles by viewModel.agentProfiles.collectAsState()
    val currentProfileId by viewModel.currentProfileId.collectAsState()
    val currentProfileName = currentProfileId
    var showAgentPicker by remember { mutableStateOf(false) }

    var inputText by remember { mutableStateOf("") }

    // Pre-fill input from share intent (checked on every ON_RESUME,
    // which fires after both onCreate and onNewIntent)
    val navigationState: NavigationState = koinInject()
    DisposableEffect(lifecycleOwner) {
        val consumeSharedText = {
            navigationState.pendingSharedText.value?.let { text ->
                inputText = text
                navigationState.pendingSharedText.value = null
            }
        }
        consumeSharedText()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                consumeSharedText()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val attachedImages = remember { mutableStateListOf<String>() }
    val attachedAudios = remember { mutableStateListOf<String>() }
    val attachedVideos = remember { mutableStateListOf<String>() }
    val attachedDocuments = remember { mutableStateListOf<Triple<String, String, String>>() } // (path, displayName, mimeType)
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Camera photo capture
    var pendingPhotoFile by remember { mutableStateOf<java.io.File?>(null) }
    var pendingPhotoUri by remember { mutableStateOf<Uri?>(null) }
    val takePhotoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            pendingPhotoFile?.absolutePath?.let { attachedImages.add(it) }
        } else {
            // User cancelled -- clean up empty file
            pendingPhotoFile?.delete()
        }
        pendingPhotoFile = null
        pendingPhotoUri = null
    }

    // Camera video capture
    var pendingVideoFile by remember { mutableStateOf<java.io.File?>(null) }
    var pendingVideoUri by remember { mutableStateOf<Uri?>(null) }
    val takeVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CaptureVideo()
    ) { success ->
        if (success) {
            pendingVideoFile?.absolutePath?.let { attachedVideos.add(it) }
        } else {
            pendingVideoFile?.delete()
        }
        pendingVideoFile = null
        pendingVideoUri = null
    }

    // Camera permission launcher
    var pendingCameraAction by remember { mutableStateOf<String?>(null) } // "photo" or "video"
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            when (pendingCameraAction) {
                "photo" -> {
                    val (file, uri) = ImageStorageHelper.createTempImageFile(context, currentConversationId)
                    pendingPhotoFile = file
                    pendingPhotoUri = uri
                    takePhotoLauncher.launch(uri)
                }
                "video" -> {
                    val (file, uri) = VideoStorageHelper.createTempVideoFile(context, currentConversationId)
                    pendingVideoFile = file
                    pendingVideoUri = uri
                    takeVideoLauncher.launch(uri)
                }
            }
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Camera permission is required")
            }
        }
        pendingCameraAction = null
    }

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

    // Location permission launcher (triggered when LLM tool needs location)
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.any { it.value }
        if (!granted) {
            scope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = "Location permission denied. Grant in app settings.",
                    actionLabel = "Open Settings",
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }
            }
        }
    }

    // Media picker launcher (images + videos)
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)
    ) { uris ->
        scope.launch {
            uris.forEach { uri ->
                val mimeType = context.contentResolver.getType(uri)
                if (mimeType?.startsWith("video/") == true) {
                    val path = withContext(Dispatchers.IO) {
                        VideoStorageHelper.copyVideoToStorage(context, uri, currentConversationId)
                    }
                    if (path != null) {
                        attachedVideos.add(path)
                    }
                } else {
                    val path = withContext(Dispatchers.IO) {
                        ImageStorageHelper.copyImageToStorage(context, uri, currentConversationId)
                    }
                    if (path != null) {
                        attachedImages.add(path)
                    }
                }
            }
        }
    }

    // Document picker launcher
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val displayName = withContext(Dispatchers.IO) {
                    DocumentStorageHelper.getFileName(context, uri)
                }
                val result = withContext(Dispatchers.IO) {
                    DocumentStorageHelper.copyDocumentToStorage(context, uri, currentConversationId)
                }
                if (result != null) {
                    val (path, mimeType) = result
                    attachedDocuments.add(Triple(path, displayName, mimeType))
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

    // Show accessibility service prompt
    LaunchedEffect(Unit) {
        viewModel.uiEvents.collect { event ->
            when (event) {
                is ChatExecutionTracker.UiEvent.AccessibilityServiceNeeded -> {
                    val result = snackbarHostState.showSnackbar(
                        message = "Accessibility service required for device control",
                        actionLabel = "Open Settings",
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        DeviceControlManager.openAccessibilitySettings(context)
                    }
                }
                is ChatExecutionTracker.UiEvent.NotificationListenerServiceNeeded -> {
                    val result = snackbarHostState.showSnackbar(
                        message = "Notification listener required for notification/media tools",
                        actionLabel = "Open Settings",
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        NotificationMediaServiceManager.openNotificationListenerSettings(context)
                    }
                }
                is ChatExecutionTracker.UiEvent.LocationPermissionNeeded -> {
                    Log.d("ChatScreen", "LocationPermissionNeeded event received")
                    val result = snackbarHostState.showSnackbar(
                        message = "Location permission required for location tools",
                        actionLabel = "Grant",
                        duration = SnackbarDuration.Long
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION
                            )
                        )
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("OneClaw")
                        if (agentProfiles.isNotEmpty()) {
                            Box {
                                TextButton(
                                    onClick = { showAgentPicker = true },
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Text(
                                        text = "Agent: ${currentProfileName ?: "main"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = "Select agent",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = showAgentPicker,
                                    onDismissRequest = { showAgentPicker = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("main") },
                                        onClick = {
                                            viewModel.setAgentProfile(null)
                                            showAgentPicker = false
                                        }
                                    )
                                    agentProfiles.filter { it.name != "main" }.forEach { profile ->
                                        DropdownMenuItem(
                                            text = { Text(profile.name) },
                                            onClick = {
                                                viewModel.setAgentProfile(profile.name)
                                                showAgentPicker = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
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
                .consumeWindowInsets(paddingValues)
                .imePadding()
        ) {
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
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
            HorizontalDivider(
                thickness = 0.5.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
            ChatInput(
                value = inputText,
                onValueChange = { inputText = it },
                onSend = {
                    viewModel.sendMessage(
                        inputText,
                        attachedImages.toList(),
                        attachedAudios.toList(),
                        attachedVideos.toList(),
                        attachedDocuments.map { Triple(it.first, it.second, it.third) }
                    )
                    inputText = ""
                    attachedImages.clear()
                    attachedAudios.clear()
                    attachedVideos.clear()
                    attachedDocuments.clear()
                },
                onStop = { viewModel.cancelRequest() },
                onPickFromGallery = {
                    imagePickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                },
                onTakePhoto = {
                    val hasCameraPermission = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!hasCameraPermission) {
                        pendingCameraAction = "photo"
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    } else {
                        val (file, uri) = ImageStorageHelper.createTempImageFile(context, currentConversationId)
                        pendingPhotoFile = file
                        pendingPhotoUri = uri
                        takePhotoLauncher.launch(uri)
                    }
                },
                onTakeVideo = {
                    val hasCameraPermission = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!hasCameraPermission) {
                        pendingCameraAction = "video"
                        cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                    } else {
                        val (file, uri) = VideoStorageHelper.createTempVideoFile(context, currentConversationId)
                        pendingVideoFile = file
                        pendingVideoUri = uri
                        takeVideoLauncher.launch(uri)
                    }
                },
                onPickDocument = {
                    documentPickerLauncher.launch(arrayOf("application/pdf", "text/*"))
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
                micAvailable = audioInputController.isMicAvailable(selectedProvider),
                attachedImages = attachedImages,
                attachedAudios = attachedAudios,
                attachedVideos = attachedVideos,
                attachedDocuments = attachedDocuments.map { it.first to it.second },
                onRemoveImage = { index -> attachedImages.removeAt(index) },
                onRemoveAudio = { index ->
                    attachedAudios.removeAt(index)
                    audioInputController.cancelRecording()
                },
                onRemoveVideo = { index -> attachedVideos.removeAt(index) },
                onRemoveDocument = { index -> attachedDocuments.removeAt(index) }
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
