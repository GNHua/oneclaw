package com.tomandy.oneclaw.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.tomandy.oneclaw.ui.chat.ChatMarkdown
import com.tomandy.oneclaw.ui.drawColumnScrollbar
import com.tomandy.oneclaw.ui.theme.Dimens
import com.tomandy.oneclaw.skill.SkillFrontmatterParser
import com.tomandy.oneclaw.skill.SkillSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillEditorScreen(
    viewModel: SkillsViewModel,
    skillName: String?,
    onNavigateBack: () -> Unit,
    onNavigateToChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val existingSkill = remember(skillName) {
        skillName?.let { viewModel.findSkill(it) }
    }
    val isBuiltIn = existingSkill?.source == SkillSource.BUNDLED
    val isEditing = existingSkill != null
    val readOnly = isBuiltIn

    // Parse existing content if editing
    val initialContent = remember(existingSkill) {
        existingSkill?.let { skill ->
            val raw = viewModel.loadRawContent(skill)
            raw?.let {
                try {
                    val parsed = SkillFrontmatterParser.parse(it)
                    Triple(
                        parsed.metadata.name,
                        parsed.metadata.description,
                        parsed.body
                    )
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    var name by remember { mutableStateOf(initialContent?.first ?: "") }
    var description by remember { mutableStateOf(initialContent?.second ?: "") }
    var body by remember { mutableStateOf(initialContent?.third ?: "") }
    var nameError by remember { mutableStateOf<String?>(null) }
    var isInstructionsEditorOpen by remember { mutableStateOf(false) }

    val saveStatus by viewModel.saveStatus.collectAsState()

    val namePattern = remember { Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?\$") }

    fun validateName(value: String): String? {
        if (value.isBlank()) return "Name is required"
        if (value.length > 64) return "Name must be 64 characters or less"
        if (!namePattern.matches(value)) {
            return "Use lowercase letters, digits, and hyphens only"
        }
        return null
    }

    // Navigate back on successful save
    LaunchedEffect(saveStatus) {
        if (saveStatus is SkillSaveStatus.Success) {
            onNavigateBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            isBuiltIn -> existingSkill!!.metadata.name
                            isEditing -> "Edit Skill"
                            else -> "New Skill"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (!readOnly && existingSkill?.source == SkillSource.USER) {
                        IconButton(onClick = {
                            viewModel.startAgentEditFlow(existingSkill)
                            onNavigateToChat()
                        }) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = "Edit with Agent"
                            )
                        }
                    }
                    if (!readOnly) {
                        IconButton(
                            onClick = {
                                nameError = validateName(name)
                                if (nameError == null && description.isNotBlank()) {
                                    viewModel.saveSkill(name, description, body)
                                }
                            },
                            enabled = name.isNotBlank() && description.isNotBlank() &&
                                saveStatus !is SkillSaveStatus.Saving
                        ) {
                            Icon(Icons.Default.Check, contentDescription = "Save")
                        }
                    }
                }
            )
        },
        modifier = modifier
    ) { paddingValues ->
        Column(Modifier.fillMaxSize().padding(paddingValues)) {
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

            val scrollState = rememberScrollState()
            val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .drawColumnScrollbar(scrollState, scrollbarColor)
                    .verticalScroll(scrollState)
                    .padding(horizontal = Dimens.ScreenPadding)
                    .padding(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Name + Description group
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (readOnly || isEditing) {
                            Column {
                                Text(
                                    text = "Name",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = name,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        } else {
                            OutlinedTextField(
                                value = name,
                                onValueChange = {
                                    name = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '-' }
                                    nameError = null
                                },
                                label = { Text("Name") },
                                placeholder = { Text("my-skill-name") },
                                supportingText = nameError?.let { error ->
                                    { Text(error, color = MaterialTheme.colorScheme.error) }
                                },
                                isError = nameError != null,
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text("Description") },
                            placeholder = { Text("A short description of what this skill does") },
                            singleLine = true,
                            readOnly = readOnly,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Instructions row -- opens full-screen editor
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isInstructionsEditorOpen = true }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Instructions",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = if (body.isEmpty()) "Empty" else "${body.length} chars",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Edit instructions",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (saveStatus is SkillSaveStatus.Saving) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                if (saveStatus is SkillSaveStatus.Error) {
                    Text(
                        text = (saveStatus as SkillSaveStatus.Error).message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                if (readOnly) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Built-in skills are read-only",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (isInstructionsEditorOpen) {
        InstructionsEditorDialog(
            value = body,
            onValueChange = { if (!readOnly) body = it },
            readOnly = readOnly,
            onDismiss = { isInstructionsEditorOpen = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstructionsEditorDialog(
    value: String,
    onValueChange: (String) -> Unit,
    readOnly: Boolean,
    onDismiss: () -> Unit
) {
    var showRaw by remember { mutableStateOf(!readOnly) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Instructions") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showRaw = !showRaw }) {
                            Icon(
                                imageVector = if (showRaw)
                                    Icons.Default.Description
                                else
                                    Icons.Default.Code,
                                contentDescription = if (showRaw) "Show rendered" else "Show raw"
                            )
                        }
                    }
                )
            }
        ) { paddingValues ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                if (showRaw) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = onValueChange,
                        readOnly = readOnly,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        textStyle = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        ),
                        placeholder = { Text("Markdown instructions for the agent...") }
                    )
                } else {
                    val scrollState = rememberScrollState()
                    val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    ChatMarkdown(
                        text = value,
                        modifier = Modifier
                            .fillMaxSize()
                            .drawColumnScrollbar(scrollState, scrollbarColor)
                            .verticalScroll(scrollState)
                            .padding(16.dp)
                    )
                }
            }
        }
    }
}
