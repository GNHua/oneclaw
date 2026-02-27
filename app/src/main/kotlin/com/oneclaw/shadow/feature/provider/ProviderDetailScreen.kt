package com.oneclaw.shadow.feature.provider

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneclaw.shadow.core.model.ModelSource
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderDetailScreen(
    onNavigateBack: () -> Unit,
    viewModel: ProviderDetailViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

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
                title = { Text(uiState.provider?.name ?: "") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                contentPadding = padding,
                modifier = Modifier.fillMaxSize()
            ) {
                // API Key Section
                item {
                    ApiKeySection(
                        uiState = uiState,
                        onToggleVisibility = viewModel::toggleApiKeyVisibility,
                        onStartEditing = viewModel::startEditingApiKey,
                        onCancelEditing = viewModel::cancelEditingApiKey,
                        onInputChange = viewModel::onApiKeyInputChange,
                        onSave = { viewModel.saveApiKey(uiState.apiKeyInput) }
                    )
                }

                // Test Connection Section
                item {
                    TestConnectionSection(
                        uiState = uiState,
                        onTestConnection = viewModel::testConnection
                    )
                }

                // Models header
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AVAILABLE MODELS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (uiState.isRefreshingModels) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                        } else {
                            TextButton(onClick = viewModel::refreshModels) {
                                Text("Refresh")
                            }
                        }
                    }
                }

                // Model list
                items(uiState.models, key = { "${it.id}_${it.providerId}" }) { model ->
                    val isDefault = model.id == uiState.globalDefaultModelId
                        && model.providerId == uiState.globalDefaultProviderId

                    ModelListItemRow(
                        modelId = model.id,
                        displayName = model.displayName,
                        source = model.source,
                        isDefault = isDefault,
                        canDelete = model.source == ModelSource.MANUAL,
                        onSetDefault = { viewModel.setDefaultModel(model.id) },
                        onDelete = { viewModel.deleteManualModel(model.id) }
                    )
                }

                // Add manual model button
                item {
                    TextButton(
                        onClick = viewModel::showAddModelDialog,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    ) {
                        Text("+ Add model manually")
                    }
                }

                // Active toggle
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Provider active", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = if (uiState.isActive) "Enabled" else "Disabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.isActive,
                            onCheckedChange = { viewModel.toggleProviderActive() }
                        )
                    }
                }

                // Delete button (custom providers only)
                if (!uiState.isPreConfigured) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = viewModel::deleteProvider,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                        ) {
                            Text("Delete Provider")
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    // Add model dialog
    if (uiState.showAddModelDialog) {
        AlertDialog(
            onDismissRequest = viewModel::hideAddModelDialog,
            title = { Text("Add model") },
            text = {
                OutlinedTextField(
                    value = uiState.manualModelIdInput,
                    onValueChange = viewModel::onManualModelIdChange,
                    label = { Text("Model ID") },
                    placeholder = { Text("e.g. llama3") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.addManualModel(uiState.manualModelIdInput.trim(), null)
                    },
                    enabled = uiState.manualModelIdInput.isNotBlank()
                ) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::hideAddModelDialog) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ApiKeySection(
    uiState: ProviderDetailUiState,
    onToggleVisibility: () -> Unit,
    onStartEditing: () -> Unit,
    onCancelEditing: () -> Unit,
    onInputChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text(
            text = "API KEY",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.isEditingApiKey) {
            OutlinedTextField(
                value = uiState.apiKeyInput,
                onValueChange = onInputChange,
                label = { Text("API Key") },
                placeholder = { Text("Paste your API key here") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancelEditing) {
                    Text("Cancel")
                }
                Button(
                    onClick = onSave,
                    enabled = uiState.apiKeyInput.isNotBlank()
                ) {
                    Text("Save")
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (uiState.apiKeyMasked.isEmpty()) "Not configured"
                           else if (uiState.apiKeyVisible) uiState.apiKeyFull
                           else uiState.apiKeyMasked,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (uiState.apiKeyMasked.isEmpty())
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                Row {
                    if (uiState.apiKeyMasked.isNotEmpty()) {
                        IconButton(onClick = onToggleVisibility) {
                            Icon(
                                imageVector = if (uiState.apiKeyVisible)
                                    Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (uiState.apiKeyVisible) "Hide" else "Show"
                            )
                        }
                    }
                    TextButton(onClick = onStartEditing) {
                        Text(if (uiState.apiKeyMasked.isEmpty()) "Add key" else "Change")
                    }
                }
            }
        }
    }
}

@Composable
private fun TestConnectionSection(
    uiState: ProviderDetailUiState,
    onTestConnection: () -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Button(
            onClick = onTestConnection,
            enabled = !uiState.isTestingConnection && uiState.apiKeyMasked.isNotEmpty(),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (uiState.isTestingConnection) {
                CircularProgressIndicator(strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(if (uiState.isTestingConnection) "Testing..." else "Test Connection")
        }

        uiState.connectionTestResult?.let { result ->
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (result.success)
                        MaterialTheme.colorScheme.tertiaryContainer
                    else
                        MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = if (result.success)
                        "Connection successful. Found ${result.modelCount} model${if ((result.modelCount ?: 0) != 1) "s" else ""}."
                    else
                        result.errorMessage ?: "Connection failed.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
    }
}

@Composable
private fun ModelListItemRow(
    modelId: String,
    displayName: String?,
    source: ModelSource,
    isDefault: Boolean,
    canDelete: Boolean,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = displayName ?: modelId,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "${modelId} · ${source.name.lowercase()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row {
            IconButton(onClick = onSetDefault) {
                Icon(
                    imageVector = if (isDefault) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (isDefault) "Default" else "Set as default",
                    tint = if (isDefault) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (canDelete) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete model",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
