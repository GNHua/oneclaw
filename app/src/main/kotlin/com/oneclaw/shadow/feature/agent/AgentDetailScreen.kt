package com.oneclaw.shadow.feature.agent

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: AgentDetailViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.navigateBack) {
        if (uiState.navigateBack) {
            viewModel.onNavigatedBack()
            onNavigateBack()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    LaunchedEffect(uiState.successMessage) {
        uiState.successMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSuccess()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            uiState.isNewAgent -> "Create Agent"
                            uiState.isBuiltIn -> uiState.name
                            else -> "Edit Agent"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!uiState.isBuiltIn) {
                        TextButton(
                            onClick = { viewModel.saveAgent() },
                            enabled = uiState.hasUnsavedChanges && !uiState.isSaving
                        ) {
                            Text("Save")
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding
            ) {
                if (uiState.isNewAgent) {
                    item {
                        PromptGenerateSection(
                            prompt = uiState.generatePrompt,
                            onPromptChange = { viewModel.updateGeneratePrompt(it) },
                            onGenerate = { viewModel.generateFromPrompt() },
                            isGenerating = uiState.isGenerating,
                            modifier = Modifier.padding(16.dp)
                        )
                        HorizontalDivider()
                    }
                }

                item {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = uiState.name,
                            onValueChange = { viewModel.updateName(it) },
                            label = { Text("Name") },
                            readOnly = uiState.isBuiltIn,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = uiState.description,
                            onValueChange = { viewModel.updateDescription(it) },
                            label = { Text("Description (optional)") },
                            readOnly = uiState.isBuiltIn,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = uiState.systemPrompt,
                            onValueChange = { viewModel.updateSystemPrompt(it) },
                            label = { Text("System Prompt *") },
                            readOnly = uiState.isBuiltIn,
                            minLines = 5,
                            maxLines = 15,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                item {
                    HorizontalDivider()
                    Text(
                        text = "PREFERRED MODEL (optional)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    PreferredModelDropdown(
                        currentProviderId = uiState.preferredProviderId,
                        currentModelId = uiState.preferredModelId,
                        availableModels = uiState.availableModels,
                        onSelect = { providerId, modelId -> viewModel.setPreferredModel(providerId, modelId) },
                        onClear = { viewModel.clearPreferredModel() },
                        enabled = !uiState.isBuiltIn,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                item {
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !uiState.isBuiltIn) {
                                viewModel.updateWebSearchEnabled(!uiState.webSearchEnabled)
                            }
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.TravelExplore, contentDescription = null)
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Web Search", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Allow the agent to search the web for up-to-date information. Additional API costs apply.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.webSearchEnabled,
                            onCheckedChange = { viewModel.updateWebSearchEnabled(it) },
                            enabled = !uiState.isBuiltIn
                        )
                    }
                }

                item {
                    HorizontalDivider()
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (!uiState.isNewAgent) {
                            TextButton(
                                onClick = { viewModel.cloneAgent() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Clone Agent")
                            }
                        }
                        if (!uiState.isBuiltIn && !uiState.isNewAgent) {
                            TextButton(
                                onClick = { viewModel.showDeleteConfirmation() },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Delete Agent", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (uiState.showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirmation() },
            title = { Text("Delete Agent") },
            text = {
                Text("This agent will be permanently removed. Any sessions using this agent will switch to General Assistant.")
            },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteAgent() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteConfirmation() }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PromptGenerateSection(
    prompt: String,
    onPromptChange: (String) -> Unit,
    onGenerate: () -> Unit,
    isGenerating: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "GENERATE FROM PROMPT",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = prompt,
            onValueChange = onPromptChange,
            label = { Text("Describe the agent you want to create...") },
            readOnly = isGenerating,
            minLines = 3,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onGenerate,
            enabled = prompt.isNotBlank() && !isGenerating,
            modifier = Modifier.align(Alignment.End)
        ) {
            if (isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text("Generate")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreferredModelDropdown(
    currentProviderId: String?,
    currentModelId: String?,
    availableModels: List<ModelOptionItem>,
    onSelect: (String, String) -> Unit,
    onClear: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val currentModel = availableModels.find {
        it.providerId == currentProviderId && it.modelId == currentModelId
    }
    val displayText = if (currentModel != null) {
        "${currentModel.providerName} / ${currentModel.modelDisplayName ?: currentModel.modelId}"
    } else {
        "Using global default"
    }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            if (currentModel != null) {
                DropdownMenuItem(
                    text = { Text("Clear (use global default)") },
                    onClick = {
                        onClear()
                        expanded = false
                    }
                )
                HorizontalDivider()
            }
            availableModels
                .groupBy { it.providerName }
                .forEach { (providerName, models) ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                providerName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick = {},
                        enabled = false
                    )
                    models.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model.modelDisplayName ?: model.modelId) },
                            onClick = {
                                onSelect(model.providerId, model.modelId)
                                expanded = false
                            },
                            trailingIcon = {
                                if (model.providerId == currentProviderId &&
                                    model.modelId == currentModelId
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = "Selected")
                                }
                            }
                        )
                    }
                }
        }
    }
}
