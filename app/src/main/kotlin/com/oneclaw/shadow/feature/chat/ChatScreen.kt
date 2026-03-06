package com.oneclaw.shadow.feature.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.DisableSelection
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import com.oneclaw.shadow.core.model.Citation
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.oneclaw.shadow.core.model.MessageType
import com.oneclaw.shadow.core.model.ToolCallStatus
import com.oneclaw.shadow.core.util.formatWithCommas
import com.oneclaw.shadow.feature.agent.AgentSelectorSheet
import com.oneclaw.shadow.core.model.Attachment
import com.oneclaw.shadow.feature.chat.components.AttachmentDisplay
import com.oneclaw.shadow.feature.chat.components.AttachmentPickerSheet
import com.oneclaw.shadow.feature.chat.components.AttachmentPreviewRow
import com.oneclaw.shadow.feature.chat.components.ImageViewerDialog
import com.oneclaw.shadow.feature.session.SessionDrawerContent
import com.oneclaw.shadow.feature.session.SessionListViewModel
import com.oneclaw.shadow.feature.skill.ui.SkillSelectionBottomSheet
import com.oneclaw.shadow.feature.skill.ui.SlashCommandPopup
import com.oneclaw.shadow.bridge.BridgeStateTracker
import com.oneclaw.shadow.feature.schedule.ExactAlarmPermissionDialog
import com.oneclaw.shadow.feature.schedule.alarm.ExactAlarmEventBus
import com.oneclaw.shadow.feature.schedule.alarm.ExactAlarmHelper
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onNavigateToSettings: () -> Unit,
    initialSessionId: String? = null,
    viewModel: ChatViewModel = koinViewModel(),
    sessionListViewModel: SessionListViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sessionListState by sessionListViewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current
    val inputFocusRequester = remember { FocusRequester() }
    val context = LocalContext.current
    val exactAlarmHelper: ExactAlarmHelper = koinInject()

    LaunchedEffect(initialSessionId) {
        if (initialSessionId != null) {
            viewModel.initialize(initialSessionId)
        }
    }

    // Attachment picker launchers
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.addAttachment(it) } }

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let { viewModel.addAttachment(it) } }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.addAttachment(it) } }

    val cameraPhotoFile = remember { mutableStateOf<java.io.File?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            cameraPhotoFile.value?.let { viewModel.addCameraPhoto(it) }
        }
    }

    // Auto-scroll state: stable MutableState so nestedScrollConnection closure never goes stale.
    // Reset to true on each new session via LaunchedEffect.
    val shouldAutoScrollState = remember { mutableStateOf(true) }
    LaunchedEffect(uiState.sessionId) { shouldAutoScrollState.value = true }
    var shouldAutoScroll by shouldAutoScrollState

    LaunchedEffect(shouldAutoScroll) {
        viewModel.setAutoScroll(shouldAutoScroll)
    }

    // Auto-scroll to the bottom of the last item (scrollOffset = MAX so the item's bottom
    // is flush with the viewport bottom, not its top).
    LaunchedEffect(uiState.messages.size, uiState.streamingText) {
        if (shouldAutoScroll) {
            val count = listState.layoutInfo.totalItemsCount
            if (count > 0) listState.scrollToItem(count - 1, Int.MAX_VALUE / 2)
        }
    }

    // Re-enable auto-scroll when the user scrolls back to the bottom.
    LaunchedEffect(Unit) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastItem = info.visibleItemsInfo.lastOrNull()
            lastItem != null &&
                lastItem.index >= info.totalItemsCount - 1 &&
                lastItem.offset + lastItem.size <= info.viewportEndOffset + 1
        }.collect { atBottom ->
            if (atBottom) shouldAutoScrollState.value = true
        }
    }

    // Switch to a new session when the bridge creates one (e.g. /clear from Telegram)
    LaunchedEffect(Unit) {
        BridgeStateTracker.newSessionFromBridge.collect { sessionId ->
            viewModel.initialize(sessionId)
        }
    }

    // Show exact alarm permission dialog when AI tool path triggers it
    var showExactAlarmDialog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        ExactAlarmEventBus.permissionNeeded.collect {
            showExactAlarmDialog = true
        }
    }

    // Disable auto-scroll the moment the user drags upward to view history.
    // Using nestedScroll (pre-scroll) is race-free: it fires before the list state changes,
    // so the programmatic scrollToItem can never beat the user gesture detection.
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0f) shouldAutoScrollState.value = false
                return Offset.Zero
            }
        }
    }

    // Session undo snackbar
    sessionListState.undoState?.let { undoState ->
        LaunchedEffect(undoState) {
            val result = snackbarHostState.showSnackbar(
                message = undoState.message,
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                sessionListViewModel.undoDelete()
            }
        }
    }

    // No provider warning
    if (!uiState.hasConfiguredProvider) {
        LaunchedEffect(Unit) {
            val result = snackbarHostState.showSnackbar(
                message = "No provider configured. Set up in Settings.",
                actionLabel = "Settings",
                duration = SnackbarDuration.Indefinite
            )
            if (result == SnackbarResult.ActionPerformed) {
                onNavigateToSettings()
            }
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                SessionDrawerContent(
                    viewModel = sessionListViewModel,
                    onNewConversation = {
                        viewModel.newConversation()
                        scope.launch { drawerState.close() }
                    },
                    onSessionClick = { sessionId ->
                        viewModel.initialize(sessionId)
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                ChatTopBar(
                    agentName = uiState.currentAgentName,
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onAgentClick = { viewModel.toggleAgentSelector() },
                    onSettingsClick = onNavigateToSettings
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                Column {
                    // RFC-014: Slash command autocomplete popup
                    if (uiState.slashCommandState.isActive &&
                        uiState.slashCommandState.matchingSkills.isNotEmpty()
                    ) {
                        SlashCommandPopup(
                            skills = uiState.slashCommandState.matchingSkills,
                            onSkillSelected = { skill ->
                                viewModel.selectSkillFromSlashCommand(skill)
                                inputFocusRequester.requestFocus()
                            }
                        )
                    }
                    // RFC-026: Attachment preview
                    if (uiState.pendingAttachments.isNotEmpty()) {
                        AttachmentPreviewRow(
                            attachments = uiState.pendingAttachments,
                            onRemove = { viewModel.removeAttachment(it) },
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    ChatInput(
                        text = uiState.inputText,
                        onTextChange = { viewModel.updateInputText(it) },
                        onSend = { viewModel.sendMessage() },
                        onStop = { viewModel.stopGeneration() },
                        onSkillClick = { viewModel.toggleSkillSheet() },
                        onAttachmentClick = { viewModel.showAttachmentPicker() },
                        isStreaming = uiState.isStreaming,
                        hasConfiguredProvider = uiState.hasConfiguredProvider,
                        hasPendingAttachments = uiState.pendingAttachments.isNotEmpty(),
                        focusRequester = inputFocusRequester
                    )
                }
            },
            // Bottom bar handles its own insets (navigationBarsPadding + imePadding).
            contentWindowInsets = WindowInsets(0, 0, 0, 0)
        ) { padding ->
            Box(modifier = Modifier.padding(padding).nestedScroll(nestedScrollConnection)) {
                if (uiState.messages.isEmpty() && !uiState.isStreaming) {
                    EmptyChatState()
                } else {
                    MessageList(
                        messages = uiState.messages,
                        streamingText = uiState.streamingText,
                        streamingThinkingText = uiState.streamingThinkingText,
                        activeToolCalls = uiState.activeToolCalls,
                        isStreaming = uiState.isStreaming,
                        isWebSearching = uiState.isWebSearching,
                        webSearchQuery = uiState.webSearchQuery,
                        listState = listState,
                        onCopy = { content -> clipboardManager.setText(AnnotatedString(content)) },
                        onRetry = { viewModel.retryLastMessage() },
                        onRegenerate = { viewModel.regenerate() },
                        onImageClick = { path -> viewModel.openImageViewer(path) }
                    )
                }

                if (!shouldAutoScroll && uiState.messages.isNotEmpty()) {
                    FloatingActionButton(
                        onClick = {
                            shouldAutoScroll = true
                            scope.launch {
                                val count = listState.layoutInfo.totalItemsCount
                                if (count > 0) listState.animateScrollToItem(count - 1)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, "Scroll to bottom")
                    }
                }
            }
        }
    }

    if (uiState.showAgentSelector) {
        AgentSelectorSheet(
            currentAgentId = uiState.currentAgentId,
            onAgentSelected = { agentId -> viewModel.switchAgent(agentId) },
            onDismiss = { viewModel.dismissAgentSelector() }
        )
    }

    // RFC-014: Skill selection bottom sheet
    if (uiState.showSkillSheet) {
        SkillSelectionBottomSheet(
            skills = uiState.allSkills,
            onSkillSelected = { skill ->
                viewModel.selectSkillFromSheet(skill)
                inputFocusRequester.requestFocus()
            },
            onDismiss = { viewModel.dismissSkillSheet() }
        )
    }

    // RFC-026: Attachment picker bottom sheet
    if (uiState.showAttachmentPicker) {
        AttachmentPickerSheet(
            onDismiss = { viewModel.hideAttachmentPicker() },
            onPickPhoto = {
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onPickVideo = {
                videoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            },
            onTakePhoto = {
                val photoDir = java.io.File(context.cacheDir, "camera_photos")
                photoDir.mkdirs()
                val file = java.io.File(photoDir, "photo_${System.currentTimeMillis()}.jpg")
                cameraPhotoFile.value = file
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
                cameraLauncher.launch(uri)
            },
            onPickFile = {
                filePickerLauncher.launch("*/*")
            }
        )
    }

    uiState.viewingImagePath?.let { imagePath ->
        ImageViewerDialog(
            imagePath = imagePath,
            onDismiss = { viewModel.closeImageViewer() }
        )
    }

    if (showExactAlarmDialog) {
        ExactAlarmPermissionDialog(
            onGoToSettings = {
                showExactAlarmDialog = false
                context.startActivity(exactAlarmHelper.buildSettingsIntent())
            },
            onDismiss = { showExactAlarmDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    agentName: String,
    onMenuClick: () -> Unit,
    onAgentClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.Menu, contentDescription = "Menu")
            }
        },
        title = {
            Row(
                modifier = Modifier
                    .clickable(onClick = onAgentClick)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = agentName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = "Switch agent",
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        actions = {
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }
    )
}

@Composable
fun ChatInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSkillClick: () -> Unit = {},
    onAttachmentClick: () -> Unit = {},
    isStreaming: Boolean,
    hasConfiguredProvider: Boolean,
    hasPendingAttachments: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() }
) {
    // Track TextFieldValue internally to control cursor position.
    // When text changes externally (e.g., skill selection), place cursor at end.
    var textFieldValue by remember { mutableStateOf(TextFieldValue(text)) }
    if (textFieldValue.text != text) {
        textFieldValue = TextFieldValue(text = text, selection = TextRange(text.length))
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(28.dp),
        modifier = Modifier
            .fillMaxWidth()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            // Layer 1: Text Field
            BasicTextField(
                value = textFieldValue,
                onValueChange = { newValue ->
                    textFieldValue = newValue
                    onTextChange(newValue.text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .padding(top = 12.dp, bottom = 4.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                minLines = 1,
                maxLines = 6,
                decorationBox = { innerTextField ->
                    Box {
                        if (text.isEmpty()) {
                            Text(
                                text = "Message or /skill",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        innerTextField()
                    }
                }
            )

            // Layer 2: Action Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Skill button (left) with background
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable(onClick = onSkillClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = "Skills",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Attachment button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable(onClick = onAttachmentClick),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AttachFile,
                        contentDescription = "Attach",
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Stop button (right, conditional)
                if (isStreaming) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .clickable(onClick = onStop),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(34.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.error
                        )
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = "Stop",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                }

                // Send button (right)
                val sendEnabled = (text.isNotBlank() || hasPendingAttachments) && hasConfiguredProvider
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            if (sendEnabled) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                        )
                        .clickable(enabled = sendEnabled, onClick = onSend),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = if (sendEnabled) MaterialTheme.colorScheme.onPrimary
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

/**
 * Reorders a flat message list so each TOOL_CALL is immediately followed by its
 * matching TOOL_RESULT (matched by toolCallId). Runs of consecutive TOOL_CALLs
 * followed by consecutive TOOL_RESULTs are interleaved into CALL-RESULT pairs.
 */
fun interleaveToolMessages(messages: List<ChatMessageItem>): List<ChatMessageItem> {
    val result = mutableListOf<ChatMessageItem>()
    var i = 0
    while (i < messages.size) {
        val msg = messages[i]
        if (msg.type == MessageType.TOOL_CALL) {
            val calls = mutableListOf<ChatMessageItem>()
            while (i < messages.size && messages[i].type == MessageType.TOOL_CALL) {
                calls.add(messages[i++])
            }
            val results = mutableListOf<ChatMessageItem>()
            while (i < messages.size && messages[i].type == MessageType.TOOL_RESULT) {
                results.add(messages[i++])
            }
            for (call in calls) {
                result.add(call)
                results.find { it.toolCallId == call.toolCallId }?.let { result.add(it) }
            }
            // Any unmatched results (shouldn't happen in normal flow)
            results.filterTo(result) { r -> calls.none { c -> c.toolCallId == r.toolCallId } }
        } else {
            result.add(msg)
            i++
        }
    }
    return result
}

@Composable
fun MessageList(
    messages: List<ChatMessageItem>,
    streamingText: String,
    streamingThinkingText: String,
    activeToolCalls: List<ActiveToolCall>,
    isStreaming: Boolean,
    isWebSearching: Boolean = false,
    webSearchQuery: String? = null,
    listState: LazyListState,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
    onRegenerate: () -> Unit,
    onImageClick: (String) -> Unit = {}
) {
    val displayMessages = remember(messages) { interleaveToolMessages(messages) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(displayMessages, key = { it.id }) { message ->
            when (message.type) {
                MessageType.USER -> UserMessageBubble(
                    content = message.content,
                    attachments = message.attachments,
                    onCopy = { onCopy(message.content) },
                    onImageClick = onImageClick
                )
                MessageType.AI_RESPONSE -> AiMessageBubble(
                    content = message.content,
                    thinkingContent = message.thinkingContent,
                    citations = message.citations,
                    modelId = message.modelId,
                    tokenCountInput = message.tokenCountInput,
                    tokenCountOutput = message.tokenCountOutput,
                    isLastAiMessage = message == displayMessages.lastOrNull { it.type == MessageType.AI_RESPONSE },
                    onCopy = { onCopy(message.content) },
                    onRegenerate = onRegenerate
                )
                MessageType.TOOL_CALL -> ToolCallCard(
                    toolName = message.toolName ?: "Unknown tool",
                    toolInput = message.toolInput,
                    status = message.toolStatus ?: ToolCallStatus.PENDING
                )
                MessageType.TOOL_RESULT -> ToolResultCard(
                    toolName = message.toolName ?: "Unknown tool",
                    toolOutput = message.toolOutput,
                    status = message.toolStatus ?: ToolCallStatus.SUCCESS,
                    durationMs = message.toolDurationMs
                )
                MessageType.ERROR -> ErrorMessageCard(
                    content = message.content,
                    isRetryable = message.isRetryable,
                    onRetry = onRetry
                )
                MessageType.SYSTEM -> SystemMessageCard(content = message.content)
            }
        }

        if (isWebSearching) {
            item(key = "web_search_indicator") {
                WebSearchIndicator(query = webSearchQuery)
            }
        }

        if (isStreaming && (streamingText.isNotEmpty() || streamingThinkingText.isNotEmpty())) {
            item(key = "streaming") {
                AiMessageBubble(
                    content = streamingText,
                    thinkingContent = streamingThinkingText.ifEmpty { null },
                    modelId = null,
                    isLastAiMessage = false,
                    onCopy = { onCopy(streamingText) },
                    onRegenerate = {},
                    isStreaming = true
                )
            }
        }

        if (activeToolCalls.isNotEmpty()) {
            items(activeToolCalls, key = { it.toolCallId }) { tc ->
                ToolCallCard(
                    toolName = tc.toolName,
                    toolInput = tc.arguments,
                    status = tc.status
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserMessageBubble(
    content: String,
    attachments: List<Attachment> = emptyList(),
    onCopy: () -> Unit,
    onImageClick: (String) -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            modifier = Modifier
                .widthIn(max = 300.dp)
                .combinedClickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = {},
                    onLongClick = onCopy
                )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (attachments.isNotEmpty()) {
                    AttachmentDisplay(
                        attachments = attachments,
                        onImageClick = { attachment -> onImageClick(attachment.filePath) },
                        onVideoClick = {},
                        onFileClick = {},
                        modifier = if (content.isNotBlank()) Modifier.padding(bottom = 8.dp) else Modifier
                    )
                }
                if (content.isNotBlank()) {
                    DisableSelection {
                        Text(
                            text = content,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AiMessageBubble(
    content: String,
    thinkingContent: String?,
    citations: List<Citation>? = null,
    modelId: String?,
    tokenCountInput: Int? = null,
    tokenCountOutput: Int? = null,
    isLastAiMessage: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    isStreaming: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        if (!thinkingContent.isNullOrBlank()) {
            ThinkingBlock(content = thinkingContent)
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (content.isNotEmpty() || isStreaming) {
            val aiInteractionSource = remember { MutableInteractionSource() }
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        interactionSource = aiInteractionSource,
                        indication = null,
                        onClick = {},
                        onLongClick = onCopy
                    )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    if (content.isNotEmpty()) {
                        Markdown(
                            content = content,
                            typography = markdownTypography(
                                h1 = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                                h2 = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
                                h3 = MaterialTheme.typography.titleSmall.copy(fontSize = 18.sp),
                                h4 = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                                h5 = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
                                h6 = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (isStreaming) {
                        StreamingCursor()
                    }
                }
            }
        }

        if (!isStreaming && !citations.isNullOrEmpty()) {
            CitationSection(
                citations = citations,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (!isStreaming && isLastAiMessage && content.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.ContentCopy, contentDescription = "Copy",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRegenerate, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Refresh, contentDescription = "Regenerate",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (modelId != null) {
                    Text(
                        text = modelId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(start = 8.dp)
                    )
                }
                if (tokenCountInput != null && tokenCountOutput != null) {
                    Text(
                        text = "${formatWithCommas(tokenCountInput)} in / ${formatWithCommas(tokenCountOutput)} out",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(start = if (modelId != null) 4.dp else 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun ThinkingBlock(content: String) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Thinking...",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (expanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun ToolCallCard(
    toolName: String,
    toolInput: String?,
    status: ToolCallStatus
) {
    var expanded by remember { mutableStateOf(false) }
    val hasInput = !toolInput.isNullOrBlank()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .then(if (hasInput) Modifier.clickable { expanded = !expanded } else Modifier)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (status) {
                    ToolCallStatus.PENDING, ToolCallStatus.EXECUTING -> {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                    ToolCallStatus.SUCCESS -> {
                        Icon(Icons.Default.CheckCircle, "Success",
                            modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                    ToolCallStatus.ERROR, ToolCallStatus.TIMEOUT -> {
                        Icon(Icons.Default.Error, "Error",
                            modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = toolName,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                if (hasInput) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = "Toggle parameters",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (expanded && hasInput) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = toolInput!!,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 20,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ToolResultCard(
    toolName: String,
    toolOutput: String?,
    status: ToolCallStatus,
    durationMs: Long?
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        color = if (status == ToolCallStatus.SUCCESS)
            MaterialTheme.colorScheme.surfaceContainerLow
        else
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$toolName result",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (durationMs != null) {
                    Text(
                        text = " (${durationMs}ms)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle",
                    modifier = Modifier.size(16.dp)
                )
            }
            if (expanded && !toolOutput.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = toolOutput,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 20,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun ErrorMessageCard(content: String, isRetryable: Boolean, onRetry: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Error, contentDescription = "Error",
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            if (isRetryable) {
                TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
fun SystemMessageCard(content: String) {
    Text(
        text = content,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun EmptyChatState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "How can I help you today?",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun CitationSection(
    citations: List<Citation>,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Column(modifier = modifier.padding(top = 8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            androidx.compose.material3.HorizontalDivider(modifier = Modifier.weight(1f))
            Text(
                text = "Sources (${citations.size})",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            androidx.compose.material3.HorizontalDivider(modifier = Modifier.weight(1f))
        }
        citations.forEach { citation ->
            CitationItem(
                citation = citation,
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(citation.url))
                    context.startActivity(intent)
                }
            )
        }
    }
}

@Composable
fun CitationItem(
    citation: Citation,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Link,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = citation.title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = citation.domain,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
fun WebSearchIndicator(query: String?) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(14.dp),
            strokeWidth = 2.dp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (query != null) "Searching: $query" else "Searching the web...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic
        )
    }
}

@Composable
fun StreamingCursor() {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500), repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )
    Text(
        text = "|",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
    )
}
