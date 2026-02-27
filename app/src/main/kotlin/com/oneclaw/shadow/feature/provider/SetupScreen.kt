package com.oneclaw.shadow.feature.provider

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneclaw.shadow.core.model.ProviderType
import org.koin.androidx.compose.koinViewModel

@Composable
fun SetupScreen(
    onComplete: () -> Unit,
    viewModel: SetupViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
        ) {
            Text(
                text = "Welcome to OneClawShadow",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Set up an AI provider to get started.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            when (uiState.step) {
                SetupStep.CHOOSE_PROVIDER -> ChooseProviderStep(
                    onSelectProvider = viewModel::selectProvider
                )
                SetupStep.ENTER_API_KEY -> EnterApiKeyStep(
                    uiState = uiState,
                    onApiKeyChange = viewModel::onApiKeyInputChange,
                    onTestConnection = viewModel::testConnection
                )
                SetupStep.SELECT_MODEL -> SelectModelStep(
                    uiState = uiState,
                    onSelectModel = viewModel::selectDefaultModel,
                    onComplete = { viewModel.completeSetup(onComplete) }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            uiState.errorMessage?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            TextButton(
                onClick = { viewModel.skip(onComplete) },
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text("Skip for now")
            }
        }
    }
}

@Composable
private fun ChooseProviderStep(onSelectProvider: (ProviderType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Step 1 of 3: Choose a provider",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        val providers = listOf(
            ProviderType.OPENAI to "OpenAI" to "GPT-4o, o1, and more",
            ProviderType.ANTHROPIC to "Anthropic" to "Claude Sonnet, Haiku, and more",
            ProviderType.GEMINI to "Google Gemini" to "Gemini 2.0 Flash, 2.5 Pro, and more"
        )

        providers.forEach { (typePair, description) ->
            val (type, name) = typePair
            OutlinedCard(
                onClick = { onSelectProvider(type) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(text = name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun EnterApiKeyStep(
    uiState: SetupUiState,
    onApiKeyChange: (String) -> Unit,
    onTestConnection: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = "Step 2 of 3: Enter API key",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )

        val providerName = when (uiState.selectedProviderType) {
            ProviderType.OPENAI -> "OpenAI"
            ProviderType.ANTHROPIC -> "Anthropic"
            ProviderType.GEMINI -> "Google Gemini"
            null -> "Provider"
        }

        Text(
            text = "Enter your $providerName API key.",
            style = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = uiState.apiKeyInput,
            onValueChange = onApiKeyChange,
            label = { Text("API Key") },
            placeholder = { Text("Paste your API key") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = onTestConnection,
            enabled = uiState.apiKeyInput.isNotBlank() && !uiState.isTestingConnection,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (uiState.isTestingConnection) {
                CircularProgressIndicator(strokeWidth = 2.dp)
            } else {
                Text("Test & Connect")
            }
        }

        uiState.connectionTestResult?.let { result ->
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
                        "Connected! Found ${result.modelCount} model${if ((result.modelCount ?: 0) != 1) "s" else ""}."
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
private fun SelectModelStep(
    uiState: SetupUiState,
    onSelectModel: (String) -> Unit,
    onComplete: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Step 3 of 3: Select default model",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(uiState.models) { model ->
                val isSelected = model.id == uiState.selectedDefaultModelId
                OutlinedCard(
                    onClick = { onSelectModel(model.id) },
                    modifier = Modifier.fillMaxWidth(),
                    border = if (isSelected)
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    else
                        CardDefaults.outlinedCardBorder()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onSelectModel(model.id) }
                        )
                        Column {
                            Text(
                                text = model.displayName ?: model.id,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = model.id,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Get Started")
        }
    }
}
