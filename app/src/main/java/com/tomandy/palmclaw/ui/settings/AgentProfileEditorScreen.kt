package com.tomandy.palmclaw.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentProfileEditorScreen(
    viewModel: AgentProfilesViewModel,
    profileName: String?,
    onNavigateBack: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf<String?>(null) }
    var filterTools by remember { mutableStateOf(false) }
    var selectedTools by remember { mutableStateOf(setOf<String>()) }
    var filterSkills by remember { mutableStateOf(false) }
    var selectedSkills by remember { mutableStateOf(setOf<String>()) }
    var isModelDropdownExpanded by remember { mutableStateOf(false) }

    var availableModels by remember { mutableStateOf(emptyList<Pair<String, com.tomandy.palmclaw.llm.LlmProvider>>()) }
    val availableTools = remember { viewModel.getAvailableToolNames() }
    val availableSkills = remember { viewModel.getAvailableSkillNames() }

    var isLoaded by remember { mutableStateOf(profileName == null) }
    val isEditing = profileName != null
    var isBundled by remember { mutableStateOf(false) }

    // Load models and existing profile
    LaunchedEffect(profileName) {
        availableModels = viewModel.getAvailableModels()
        if (profileName != null) {
            val profile = viewModel.findProfile(profileName)
            if (profile != null) {
                name = profile.name
                description = profile.description
                systemPrompt = profile.systemPrompt
                selectedModel = profile.model
                isBundled = profile.source == com.tomandy.palmclaw.agent.profile.AgentProfileSource.BUNDLED
                profile.allowedTools?.let {
                    filterTools = true
                    selectedTools = it.toSet()
                }
                profile.enabledSkills?.let {
                    filterSkills = true
                    selectedSkills = it.toSet()
                }
            }
            isLoaded = true
        }
    }

    if (!isLoaded) return

    val canSave = name.isNotBlank() && description.isNotBlank() && systemPrompt.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit Profile" else "New Profile") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            val allowedTools = if (filterTools) selectedTools.toList() else null
                            val enabledSkills = if (filterSkills) selectedSkills.toList() else null

                            viewModel.saveProfile(
                                name = name.trim(),
                                description = description.trim(),
                                systemPrompt = systemPrompt.trim(),
                                model = selectedModel,
                                allowedTools = allowedTools,
                                enabledSkills = enabledSkills,
                                originalName = profileName
                            )
                            onNavigateBack()
                        },
                        enabled = canSave
                    ) {
                        Icon(Icons.Default.Check, "Save")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Name (kebab-case, immutable when editing)
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.lowercase().replace(Regex("[^a-z0-9-]"), "") },
                    label = { Text("Name (kebab-case)") },
                    singleLine = true,
                    enabled = !isBundled,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Description
            item {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // System Prompt
            item {
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text("System Prompt") },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Model selector
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Model",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Override the global model for this profile",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Box {
                            TextButton(onClick = { isModelDropdownExpanded = true }) {
                                Text(selectedModel ?: "Use Default")
                                Icon(
                                    Icons.Default.ArrowDropDown,
                                    contentDescription = "Select model"
                                )
                            }
                            DropdownMenu(
                                expanded = isModelDropdownExpanded,
                                onDismissRequest = { isModelDropdownExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Use Default") },
                                    onClick = {
                                        selectedModel = null
                                        isModelDropdownExpanded = false
                                    }
                                )
                                HorizontalDivider()
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
                                            isModelDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Tool filter
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Filter Tools",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (filterTools)
                                        "${selectedTools.size} of ${availableTools.size} selected"
                                    else
                                        "All tools available",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = filterTools,
                                onCheckedChange = { filterTools = it }
                            )
                        }
                        if (filterTools && availableTools.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            availableTools.forEach { toolName ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedTools = if (toolName in selectedTools) {
                                                selectedTools - toolName
                                            } else {
                                                selectedTools + toolName
                                            }
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = toolName in selectedTools,
                                        onCheckedChange = {
                                            selectedTools = if (it) {
                                                selectedTools + toolName
                                            } else {
                                                selectedTools - toolName
                                            }
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = toolName,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Skill filter
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Filter Skills",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = if (filterSkills)
                                        "${selectedSkills.size} of ${availableSkills.size} selected"
                                    else
                                        "Global skill preferences",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = filterSkills,
                                onCheckedChange = { filterSkills = it }
                            )
                        }
                        if (filterSkills && availableSkills.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            availableSkills.forEach { skillName ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedSkills = if (skillName in selectedSkills) {
                                                selectedSkills - skillName
                                            } else {
                                                selectedSkills + skillName
                                            }
                                        }
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = skillName in selectedSkills,
                                        onCheckedChange = {
                                            selectedSkills = if (it) {
                                                selectedSkills + skillName
                                            } else {
                                                selectedSkills - skillName
                                            }
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = skillName,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
