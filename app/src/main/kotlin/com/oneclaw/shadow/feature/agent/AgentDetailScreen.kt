package com.oneclaw.shadow.feature.agent

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = padding
            ) {
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
                        text = "TOOLS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                items(uiState.availableTools, key = { it.name }) { tool ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = tool.name in uiState.selectedToolIds,
                            onCheckedChange = { viewModel.toggleTool(tool.name) },
                            enabled = !uiState.isBuiltIn
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = tool.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                text = tool.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
                    PreferredModelSelector(
                        currentProviderId = uiState.preferredProviderId,
                        currentModelId = uiState.preferredModelId,
                        availableModels = uiState.availableModels,
                        onSelect = { providerId, modelId -> viewModel.setPreferredModel(providerId, modelId) },
                        onClear = { viewModel.clearPreferredModel() },
                        enabled = !uiState.isBuiltIn,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

                item {
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                    Column(modifier = Modifier.padding(16.dp)) {
                        if (uiState.isBuiltIn) {
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
private fun PreferredModelSelector(
    currentProviderId: String?,
    currentModelId: String?,
    availableModels: List<ModelOptionItem>,
    onSelect: (String, String) -> Unit,
    onClear: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        val currentModel = availableModels.find { it.modelId == currentModelId }
        if (currentModel != null) {
            Text(
                text = "${currentModel.providerName} / ${currentModel.modelDisplayName ?: currentModel.modelId}",
                style = MaterialTheme.typography.bodyMedium
            )
            if (enabled) {
                TextButton(onClick = onClear) { Text("Clear") }
            }
        } else {
            Text(
                text = "Using global default",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (enabled && availableModels.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            availableModels.take(10).forEach { model ->
                TextButton(
                    onClick = { onSelect(model.providerId, model.modelId) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("${model.providerName} / ${model.modelDisplayName ?: model.modelId}")
                }
            }
        }
    }
}
