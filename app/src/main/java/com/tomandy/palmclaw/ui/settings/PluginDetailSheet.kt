package com.tomandy.palmclaw.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .drawScrollbar(listState, scrollbarColor, heightCache),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(pluginState.metadata.tools, key = { it.name }) { tool ->
                ToolItem(tool)
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
