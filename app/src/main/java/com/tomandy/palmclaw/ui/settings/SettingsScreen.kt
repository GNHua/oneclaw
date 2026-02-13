package com.tomandy.palmclaw.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tomandy.palmclaw.llm.LlmProvider

/**
 * Settings screen for managing API keys.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val providers by viewModel.providers.collectAsState()
    val saveStatus by viewModel.saveStatus.collectAsState()
    val deleteStatus by viewModel.deleteStatus.collectAsState()

    var selectedProvider by remember { mutableStateOf(LlmProvider.OPENAI) }
    var isProviderDropdownExpanded by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var isApiKeyVisible by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Load saved base URL when provider changes
    LaunchedEffect(selectedProvider) {
        if (selectedProvider.supportsBaseUrl) {
            baseUrl = viewModel.getBaseUrl(selectedProvider.displayName)
        } else {
            baseUrl = ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API Key Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = {
            // Show snackbar for errors
            when (val status = saveStatus) {
                is SaveStatus.Error -> {
                    Snackbar(
                        modifier = Modifier.padding(16.dp),
                        action = {
                            TextButton(onClick = { viewModel.resetSaveStatus() }) {
                                Text("Dismiss")
                            }
                        }
                    ) {
                        Text(status.message)
                    }
                }
                else -> {}
            }

            when (val status = deleteStatus) {
                is DeleteStatus.Error -> {
                    Snackbar(
                        modifier = Modifier.padding(16.dp),
                        action = {
                            TextButton(onClick = { viewModel.resetDeleteStatus() }) {
                                Text("Dismiss")
                            }
                        }
                    ) {
                        Text(status.message)
                    }
                }
                else -> {}
            }
        },
        modifier = modifier
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Add new API key section
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Add API Key",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Provider dropdown
                        ExposedDropdownMenuBox(
                            expanded = isProviderDropdownExpanded,
                            onExpandedChange = {
                                if (saveStatus !is SaveStatus.Saving) {
                                    isProviderDropdownExpanded = !isProviderDropdownExpanded
                                }
                            }
                        ) {
                            OutlinedTextField(
                                value = selectedProvider.displayName,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("LLM Provider") },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.ArrowDropDown,
                                        contentDescription = "Select provider"
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                                enabled = saveStatus !is SaveStatus.Saving,
                                colors = OutlinedTextFieldDefaults.colors()
                            )

                            ExposedDropdownMenu(
                                expanded = isProviderDropdownExpanded,
                                onDismissRequest = { isProviderDropdownExpanded = false }
                            ) {
                                LlmProvider.entries.forEach { provider ->
                                    DropdownMenuItem(
                                        text = { Text(provider.displayName) },
                                        onClick = {
                                            selectedProvider = provider
                                            isProviderDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text(selectedProvider.apiKeyLabel) },
                            placeholder = { Text("Enter your ${selectedProvider.displayName} API key") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = saveStatus !is SaveStatus.Saving,
                            singleLine = true,
                            visualTransformation = if (isApiKeyVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                TextButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                                    Text(
                                        text = if (isApiKeyVisible) "Hide" else "Show",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = if (selectedProvider.supportsBaseUrl) ImeAction.Next else ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    if (!selectedProvider.supportsBaseUrl) {
                                        keyboardController?.hide()
                                        if (apiKey.isNotBlank()) {
                                            viewModel.saveApiKey(selectedProvider.displayName, apiKey)
                                            apiKey = ""
                                            isApiKeyVisible = false
                                        }
                                    }
                                }
                            )
                        )

                        // Base URL field for providers that support it
                        if (selectedProvider.supportsBaseUrl) {
                            OutlinedTextField(
                                value = baseUrl,
                                onValueChange = { baseUrl = it },
                                label = { Text("Base URL (optional)") },
                                placeholder = { Text("https://api.anthropic.com (default)") },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = saveStatus !is SaveStatus.Saving,
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Uri,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        keyboardController?.hide()
                                        if (apiKey.isNotBlank()) {
                                            viewModel.saveApiKey(selectedProvider.displayName, apiKey)
                                            viewModel.saveBaseUrl(selectedProvider.displayName, baseUrl)
                                            apiKey = ""
                                            isApiKeyVisible = false
                                        }
                                    }
                                )
                            )
                        }

                        Button(
                            onClick = {
                                keyboardController?.hide()
                                viewModel.saveApiKey(selectedProvider.displayName, apiKey)
                                if (selectedProvider.supportsBaseUrl) {
                                    viewModel.saveBaseUrl(selectedProvider.displayName, baseUrl)
                                }
                                apiKey = ""
                                isApiKeyVisible = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = apiKey.isNotBlank() && saveStatus !is SaveStatus.Saving
                        ) {
                            when (saveStatus) {
                                SaveStatus.Saving -> {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Saving...")
                                }
                                SaveStatus.Success -> {
                                    Text("Saved!")
                                }
                                else -> {
                                    Text("Save API Key")
                                }
                            }
                        }
                    }
                }
            }

            // Saved providers section
            item {
                Text(
                    text = "Saved Providers",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (providers.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No API keys saved yet",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            } else {
                items(providers) { provider ->
                    ProviderCard(
                        provider = provider,
                        onDelete = { viewModel.deleteApiKey(provider) },
                        isDeleting = deleteStatus is DeleteStatus.Deleting
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderCard(
    provider: String,
    onDelete: () -> Unit,
    isDeleting: Boolean,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = provider,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "API key configured",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(
                onClick = onDelete,
                enabled = !isDeleting
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete $provider API key",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}
