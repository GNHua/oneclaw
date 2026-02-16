package com.tomandy.palmclaw.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomandy.palmclaw.skill.SkillFrontmatterParser
import com.tomandy.palmclaw.skill.SkillSource

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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
                readOnly = readOnly || isEditing,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description") },
                placeholder = { Text("A short description of what this skill does") },
                singleLine = true,
                readOnly = readOnly,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Instructions") },
                placeholder = { Text("Markdown instructions for the agent...") },
                readOnly = readOnly,
                minLines = 10,
                modifier = Modifier.fillMaxWidth()
            )

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
