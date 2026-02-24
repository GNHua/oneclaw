package com.tomandy.oneclaw.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.tomandy.oneclaw.ui.drawColumnScrollbar
import com.tomandy.oneclaw.ui.theme.Dimens
import com.tomandy.oneclaw.ui.theme.settingsTextFieldColors
import com.tomandy.oneclaw.ui.theme.settingsTextFieldShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentProfileEditorScreen(
    viewModel: AgentProfilesViewModel,
    profileName: String?,
    onNavigateBack: () -> Unit,
    onNavigateToSystemPrompt: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selectedModel by remember { mutableStateOf<String?>(null) }
    var filterTools by remember { mutableStateOf(false) }
    var selectedTools by remember { mutableStateOf(setOf<String>()) }
    var filterSkills by remember { mutableStateOf(false) }
    var selectedSkills by remember { mutableStateOf(setOf<String>()) }
    var temperature by remember { mutableStateOf(com.tomandy.oneclaw.agent.profile.DEFAULT_TEMPERATURE) }
    var maxIterations by remember { mutableStateOf(com.tomandy.oneclaw.agent.profile.DEFAULT_MAX_ITERATIONS) }

    var availableModels by remember { mutableStateOf(emptyList<Pair<String, com.tomandy.oneclaw.llm.LlmProvider>>()) }
    val availableTools = remember { viewModel.getAvailableToolNames() }
    val availableSkills = remember { viewModel.getAvailableSkillNames() }

    var isLoaded by remember { mutableStateOf(profileName == null) }
    val isEditing = profileName != null
    var isBundled by remember { mutableStateOf(false) }

    // Expand states
    var isModelExpanded by remember { mutableStateOf(false) }
    var isTemperatureExpanded by remember { mutableStateOf(false) }
    var isMaxIterationsExpanded by remember { mutableStateOf(false) }
    var isToolsExpanded by remember { mutableStateOf(false) }
    var isSkillsExpanded by remember { mutableStateOf(false) }

    // Load models and existing profile
    LaunchedEffect(profileName) {
        availableModels = viewModel.getAvailableModels()
        if (profileName != null) {
            val profile = viewModel.findProfile(profileName)
            if (profile != null) {
                name = profile.name
                description = profile.description
                viewModel.draftSystemPrompt = profile.systemPrompt
                selectedModel = profile.model
                isBundled = profile.source == com.tomandy.oneclaw.agent.profile.AgentProfileSource.BUNDLED
                profile.allowedTools?.let {
                    filterTools = true
                    selectedTools = it.toSet()
                }
                profile.enabledSkills?.let {
                    filterSkills = true
                    selectedSkills = it.toSet()
                }
                temperature = profile.temperature
                maxIterations = profile.maxIterations
            }
            isLoaded = true
        } else {
            viewModel.draftSystemPrompt = ""
        }
    }

    if (!isLoaded) return

    val canSave = name.isNotBlank() && description.isNotBlank() && viewModel.draftSystemPrompt.isNotBlank()

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
                                systemPrompt = viewModel.draftSystemPrompt.trim(),
                                model = selectedModel,
                                allowedTools = allowedTools,
                                enabledSkills = enabledSkills,
                                temperature = temperature,
                                maxIterations = maxIterations,
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
                        if (isBundled) {
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
                                onValueChange = { name = it.lowercase().replace(Regex("[^a-z0-9-]"), "") },
                                label = { Text("Name (kebab-case)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = settingsTextFieldShape,
                                colors = settingsTextFieldColors()
                            )
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text("Description") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            shape = settingsTextFieldShape,
                            colors = settingsTextFieldColors()
                        )
                    }
                }

                // System Prompt row -- opens full-screen editor
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onNavigateToSystemPrompt() }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "System Prompt",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = if (viewModel.draftSystemPrompt.isEmpty()) "Empty"
                            else "${viewModel.draftSystemPrompt.length} chars",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Edit system prompt",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Model selector group
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isModelExpanded = !isModelExpanded }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Model",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = selectedModel ?: "Use Default",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isModelExpanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.rotate(if (isModelExpanded) 180f else 0f)
                            )
                        }
                        AnimatedVisibility(
                            visible = isModelExpanded,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            Column {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                // Use Default option
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedModel = null
                                            isModelExpanded = false
                                        }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = selectedModel == null,
                                        onClick = {
                                            selectedModel = null
                                            isModelExpanded = false
                                        }
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = "Use Default",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                                availableModels.forEach { (model, provider) ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedModel = model
                                                isModelExpanded = false
                                            }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        RadioButton(
                                            selected = model == selectedModel,
                                            onClick = {
                                                selectedModel = model
                                                isModelExpanded = false
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(
                                                text = model,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                text = provider.displayName,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Temperature group
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isTemperatureExpanded = !isTemperatureExpanded }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Temperature",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "%.1f".format(temperature),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isTemperatureExpanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.rotate(if (isTemperatureExpanded) 180f else 0f)
                            )
                        }
                        AnimatedVisibility(
                            visible = isTemperatureExpanded,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            Column {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                Column(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                ) {
                                    Slider(
                                        value = temperature,
                                        onValueChange = { temperature = (it * 10).toInt() / 10f },
                                        valueRange = 0f..2f,
                                        steps = 19,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }

                // Max Iterations group
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isMaxIterationsExpanded = !isMaxIterationsExpanded }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Max Iterations",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = if (maxIterations >= 500) "Unlimited" else "$maxIterations",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isMaxIterationsExpanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.rotate(if (isMaxIterationsExpanded) 180f else 0f)
                            )
                        }
                        AnimatedVisibility(
                            visible = isMaxIterationsExpanded,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            Column {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                Column(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                ) {
                                    Slider(
                                        value = maxIterations.toFloat(),
                                        onValueChange = { maxIterations = it.toInt() },
                                        valueRange = 1f..500f,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Text(
                                        text = "Set to 500 for unlimited iterations",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }

                // Filter Tools group
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isToolsExpanded = !isToolsExpanded }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Filter Tools",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = if (filterTools)
                                    "${selectedTools.size} of ${availableTools.size}"
                                else
                                    "All tools",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isToolsExpanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.rotate(if (isToolsExpanded) 180f else 0f)
                            )
                        }
                        AnimatedVisibility(
                            visible = isToolsExpanded,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            Column {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                Column(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Enable tool filter",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Switch(
                                            checked = filterTools,
                                            onCheckedChange = { filterTools = it }
                                        )
                                    }
                                    if (filterTools && availableTools.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
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
                    }
                }

                // Filter Skills group
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isSkillsExpanded = !isSkillsExpanded }
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Filter Skills",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = if (filterSkills)
                                    "${selectedSkills.size} of ${availableSkills.size}"
                                else
                                    "Global preferences",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Icon(
                                imageVector = Icons.Default.KeyboardArrowDown,
                                contentDescription = if (isSkillsExpanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.rotate(if (isSkillsExpanded) 180f else 0f)
                            )
                        }
                        AnimatedVisibility(
                            visible = isSkillsExpanded,
                            enter = expandVertically(),
                            exit = shrinkVertically()
                        ) {
                            Column {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                                Column(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Enable skill filter",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Switch(
                                            checked = filterSkills,
                                            onCheckedChange = { filterSkills = it }
                                        )
                                    }
                                    if (filterSkills && availableSkills.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(4.dp))
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
        }
    }
}
