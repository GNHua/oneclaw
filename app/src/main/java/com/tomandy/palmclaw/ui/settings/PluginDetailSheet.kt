package com.tomandy.palmclaw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.tomandy.palmclaw.engine.ToolDefinition
import com.tomandy.palmclaw.ui.HandleDismissBottomSheet
import com.tomandy.palmclaw.ui.drawScrollbar
import com.tomandy.palmclaw.ui.rememberLazyListHeightCache
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun PluginDetailSheet(
    pluginState: PluginUiState,
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit
) {
    HandleDismissBottomSheet(
        onDismissRequest = onDismiss,
        header = {
            Text(
                text = pluginState.metadata.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )
        }
    ) {
        val listState = rememberLazyListState()
        val heightCache = rememberLazyListHeightCache()
        val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        val credentials = pluginState.metadata.credentials

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .drawScrollbar(listState, scrollbarColor, heightCache)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (credentials.isNotEmpty()) {
                item(key = "_credentials_header") {
                    Text(
                        text = "Configuration",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }
                item(key = "_credentials_form") {
                    CredentialForm(
                        pluginId = pluginState.metadata.id,
                        credentials = credentials,
                        viewModel = viewModel
                    )
                }
            }

            items(pluginState.metadata.tools, key = { it.name }) { tool ->
                ToolItem(tool)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CredentialForm(
    pluginId: String,
    credentials: List<com.tomandy.palmclaw.engine.CredentialDefinition>,
    viewModel: SettingsViewModel
) {
    val values = remember { mutableStateMapOf<String, String>() }
    var dirty by remember { mutableStateOf(false) }

    // Resolve the storage key for a credential, incorporating scope prefix if needed
    fun storageKey(cred: com.tomandy.palmclaw.engine.CredentialDefinition): String {
        if (cred.scopedBy.isNotEmpty()) {
            val scope = values[cred.scopedBy] ?: ""
            if (scope.isNotEmpty()) return "${scope}_${cred.key}"
        }
        return cred.key
    }

    // Load all credentials (dropdown values + scoped fields)
    LaunchedEffect(pluginId) {
        // Load dropdowns first
        credentials.filter { it.options.isNotEmpty() }.forEach { cred ->
            values[cred.key] = viewModel.getPluginCredential(pluginId, cred.key)
        }
        // Then load scoped fields using the resolved dropdown values
        credentials.filter { it.options.isEmpty() }.forEach { cred ->
            val key = if (cred.scopedBy.isNotEmpty()) {
                val scope = values[cred.scopedBy] ?: ""
                if (scope.isNotEmpty()) "${scope}_${cred.key}" else cred.key
            } else cred.key
            values[cred.key] = viewModel.getPluginCredential(pluginId, key)
        }
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            credentials.forEach { cred ->
                if (cred.options.isNotEmpty()) {
                    // Dropdown -- saves immediately, reloads scoped fields
                    var expanded by remember { mutableStateOf(false) }
                    val coroutineScope = rememberCoroutineScope()
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = it }
                    ) {
                        OutlinedTextField(
                            value = values[cred.key] ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text(cred.label) },
                            supportingText = if (cred.description.isNotEmpty()) {
                                { Text(cred.description) }
                            } else null,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            cred.options.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(option) },
                                    onClick = {
                                        values[cred.key] = option
                                        viewModel.savePluginCredential(pluginId, cred.key, option)
                                        expanded = false
                                        // Reload scoped fields for the new selection
                                        coroutineScope.launch {
                                            credentials.filter { it.scopedBy == cred.key }.forEach { scoped ->
                                                values[scoped.key] = viewModel.getPluginCredential(
                                                    pluginId, "${option}_${scoped.key}"
                                                )
                                            }
                                            dirty = false
                                        }
                                    }
                                )
                            }
                        }
                    }
                } else {
                    val isSensitive = cred.key.contains("key", ignoreCase = true) ||
                        cred.key.contains("secret", ignoreCase = true)
                    var visible by remember { mutableStateOf(false) }

                    OutlinedTextField(
                        value = values[cred.key] ?: "",
                        onValueChange = {
                            values[cred.key] = it
                            dirty = true
                        },
                        label = { Text(cred.label) },
                        supportingText = if (cred.description.isNotEmpty()) {
                            { Text(cred.description) }
                        } else null,
                        singleLine = true,
                        visualTransformation = if (isSensitive && !visible) {
                            PasswordVisualTransformation()
                        } else {
                            VisualTransformation.None
                        },
                        trailingIcon = if (isSensitive) {
                            {
                                IconButton(onClick = { visible = !visible }) {
                                    Icon(
                                        imageVector = if (visible) Icons.Default.VisibilityOff
                                            else Icons.Default.Visibility,
                                        contentDescription = if (visible) "Hide" else "Show"
                                    )
                                }
                            }
                        } else null,
                        keyboardOptions = if (isSensitive) {
                            KeyboardOptions(keyboardType = KeyboardType.Password)
                        } else {
                            KeyboardOptions.Default
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Button(
                onClick = {
                    credentials.filter { it.options.isEmpty() }.forEach { cred ->
                        viewModel.savePluginCredential(pluginId, storageKey(cred), values[cred.key] ?: "")
                    }
                    dirty = false
                },
                enabled = dirty,
                modifier = Modifier.align(Alignment.End)
            ) {
                if (!dirty) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Saved")
                } else {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun ToolItem(tool: ToolDefinition) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = tool.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            if (tool.description.isNotBlank()) {
                Text(
                    text = tool.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val params = parseParameters(tool.parameters)
            if (params.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    params.forEach { param ->
                        ParameterChip(param)
                    }
                }
            }
        }
    }
}

@Composable
private fun ParameterChip(param: ParameterInfo) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest
    ) {
        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(
                text = buildString {
                    append(param.name)
                    append(": ")
                    append(param.type)
                    if (!param.required) append(" (optional)")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (param.description.isNotBlank()) {
                Text(
                    text = param.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private data class ParameterInfo(
    val name: String,
    val type: String,
    val required: Boolean,
    val description: String
)

private fun parseParameters(schema: JsonObject): List<ParameterInfo> {
    val properties = schema["properties"] as? JsonObject ?: return emptyList()
    val required = (schema["required"] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.content }
        ?.toSet()
        ?: emptySet()

    return properties.entries.map { (name, value) ->
        val prop = value as? JsonObject
        ParameterInfo(
            name = name,
            type = (prop?.get("type") as? JsonPrimitive)?.content ?: "any",
            required = name in required,
            description = (prop?.get("description") as? JsonPrimitive)?.content ?: ""
        )
    }
}
