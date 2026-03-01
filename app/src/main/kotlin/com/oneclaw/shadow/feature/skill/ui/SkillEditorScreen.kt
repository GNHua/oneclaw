package com.oneclaw.shadow.feature.skill.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.feature.skill.SkillEditorViewModel
import org.koin.androidx.compose.koinViewModel

/**
 * Screen for creating or editing a skill.
 * In edit mode, skillName is non-null and the form is pre-populated.
 * Built-in skills are shown in read-only mode.
 * RFC-014
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillEditorScreen(
    skillName: String?,
    onNavigateBack: () -> Unit,
    viewModel: SkillEditorViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Load existing skill if editing
    LaunchedEffect(skillName) {
        if (skillName != null) {
            viewModel.loadSkill(skillName)
        }
    }

    // Handle save result
    LaunchedEffect(uiState.saveResult) {
        when (val result = uiState.saveResult) {
            is AppResult.Success -> {
                snackbarHostState.showSnackbar("Skill saved")
                viewModel.clearSaveResult()
                onNavigateBack()
            }
            is AppResult.Error -> {
                snackbarHostState.showSnackbar("Error: ${result.message}")
                viewModel.clearSaveResult()
            }
            null -> Unit
        }
    }

    val isReadOnly = uiState.isBuiltIn
    val title = when {
        isReadOnly -> "View Skill"
        uiState.isEditMode -> "Edit Skill"
        else -> "Create Skill"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // Name field (immutable in edit mode)
            OutlinedTextField(
                value = uiState.name,
                onValueChange = { if (!isReadOnly && !uiState.isEditMode) viewModel.updateName(it) },
                label = { Text("Skill ID (e.g. summarize-file)") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isReadOnly && !uiState.isEditMode,
                isError = uiState.validationErrors.containsKey("name"),
                supportingText = uiState.validationErrors["name"]?.let { { Text(it) } }
                    ?: if (!uiState.isEditMode) {
                        { Text("Lowercase letters, digits, and hyphens only") }
                    } else null,
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Display name
            OutlinedTextField(
                value = uiState.displayName,
                onValueChange = { if (!isReadOnly) viewModel.updateDisplayName(it) },
                label = { Text("Display Name") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isReadOnly,
                isError = uiState.validationErrors.containsKey("displayName"),
                supportingText = uiState.validationErrors["displayName"]?.let { { Text(it) } },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Description
            OutlinedTextField(
                value = uiState.description,
                onValueChange = { if (!isReadOnly) viewModel.updateDescription(it) },
                label = { Text("Description") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isReadOnly,
                isError = uiState.validationErrors.containsKey("description"),
                supportingText = uiState.validationErrors["description"]?.let { { Text(it) } },
                maxLines = 3
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Version
            OutlinedTextField(
                value = uiState.version,
                onValueChange = { if (!isReadOnly) viewModel.updateVersion(it) },
                label = { Text("Version") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isReadOnly,
                singleLine = true
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Prompt content
            Text(
                text = "Prompt Content",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = uiState.promptContent,
                onValueChange = { if (!isReadOnly) viewModel.updatePromptContent(it) },
                label = { Text("Prompt instructions (Markdown)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                enabled = !isReadOnly,
                isError = uiState.validationErrors.containsKey("promptContent"),
                supportingText = uiState.validationErrors["promptContent"]?.let { { Text(it) } }
                    ?: if (!isReadOnly) {
                        { Text("Use {{param_name}} for parameter substitution") }
                    } else null,
                maxLines = 30
            )
            Spacer(modifier = Modifier.height(24.dp))

            if (!isReadOnly) {
                Button(
                    onClick = { viewModel.save() },
                    enabled = !uiState.isSaving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    }
                    Text("Save Skill")
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
