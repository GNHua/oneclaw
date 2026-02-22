package com.tomandy.oneclaw.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tomandy.oneclaw.llm.LlmProvider
import com.tomandy.oneclaw.ui.drawColumnScrollbar

@Composable
fun ProvidersScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val providers by viewModel.providers.collectAsState()
    val saveStatus by viewModel.saveStatus.collectAsState()
    val deleteStatus by viewModel.deleteStatus.collectAsState()

    var expandedProvider by remember { mutableStateOf<LlmProvider?>(null) }

    val scrollState = rememberScrollState()
    val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

    Column(
        modifier = modifier
            .fillMaxSize()
            .drawColumnScrollbar(scrollState, scrollbarColor)
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        LlmProvider.entries.forEach { provider ->
            ProviderGroup(
                provider = provider,
                isConfigured = provider.displayName in providers,
                isExpanded = expandedProvider == provider,
                onToggle = {
                    expandedProvider = if (expandedProvider == provider) null else provider
                },
                viewModel = viewModel,
                saveStatus = saveStatus,
                deleteStatus = deleteStatus
            )
        }
    }
}

@Composable
private fun ProviderGroup(
    provider: LlmProvider,
    isConfigured: Boolean,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    viewModel: SettingsViewModel,
    saveStatus: SaveStatus,
    deleteStatus: DeleteStatus
) {
    var apiKey by remember(provider) { mutableStateOf("") }
    var baseUrl by remember(provider) { mutableStateOf("") }
    var isApiKeyVisible by remember(provider) { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(provider, isExpanded) {
        if (isExpanded) {
            apiKey = viewModel.getApiKey(provider.displayName)
            baseUrl = viewModel.getBaseUrl(provider.displayName)
            isApiKeyVisible = false
        }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isConfigured || isExpanded) 1f else 0.5f)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Collapsed header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = provider.displayName,
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = if (isConfigured) "Configured" else "Not configured",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(if (isExpanded) 180f else 0f)
                )
            }

            // Expanded content
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedTextField(
                            value = apiKey,
                            onValueChange = { apiKey = it },
                            label = { Text(provider.apiKeyLabel) },
                            placeholder = { Text("Enter your ${provider.displayName} API key") },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = saveStatus !is SaveStatus.Saving,
                            singleLine = true,
                            visualTransformation = if (isApiKeyVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            trailingIcon = {
                                IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                                    Icon(
                                        imageVector = if (isApiKeyVisible) Icons.Default.VisibilityOff
                                            else Icons.Default.Visibility,
                                        contentDescription = if (isApiKeyVisible) "Hide" else "Show"
                                    )
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Next
                            )
                        )

                        OutlinedTextField(
                            value = baseUrl,
                            onValueChange = { baseUrl = it },
                            label = { Text("Base URL (optional)") },
                            placeholder = { Text("Custom endpoint URL") },
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
                                        viewModel.saveApiKey(provider.displayName, apiKey)
                                        viewModel.saveBaseUrl(provider.displayName, baseUrl)
                                        apiKey = ""
                                        isApiKeyVisible = false
                                    }
                                }
                            )
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = {
                                    keyboardController?.hide()
                                    viewModel.saveApiKey(provider.displayName, apiKey)
                                    viewModel.saveBaseUrl(provider.displayName, baseUrl)
                                    apiKey = ""
                                    isApiKeyVisible = false
                                },
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
                                    SaveStatus.Success -> Text("Saved!")
                                    else -> Text("Save")
                                }
                            }

                            if (isConfigured) {
                                Spacer(modifier = Modifier.weight(1f))
                                OutlinedButton(
                                    onClick = {
                                        keyboardController?.hide()
                                        viewModel.deleteApiKey(provider.displayName)
                                        apiKey = ""
                                        baseUrl = ""
                                        isApiKeyVisible = false
                                    },
                                    enabled = deleteStatus !is DeleteStatus.Deleting,
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    if (deleteStatus is DeleteStatus.Deleting) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                    }
                                    Text("Delete")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
