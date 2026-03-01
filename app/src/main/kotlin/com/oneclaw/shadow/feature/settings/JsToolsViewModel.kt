package com.oneclaw.shadow.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneclaw.shadow.tool.engine.ToolRegistry
import com.oneclaw.shadow.tool.js.EnvironmentVariableStore
import com.oneclaw.shadow.tool.js.JsTool
import com.oneclaw.shadow.tool.js.JsToolLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for JS tools and environment variables management in Settings.
 */
class JsToolsViewModel(
    private val toolRegistry: ToolRegistry,
    private val jsToolLoader: JsToolLoader,
    private val envVarStore: EnvironmentVariableStore
) : ViewModel() {

    private val _jsToolsState = MutableStateFlow(JsToolsUiState())
    val jsToolsState: StateFlow<JsToolsUiState> = _jsToolsState.asStateFlow()

    private val _envVarsState = MutableStateFlow<List<EnvVarEntry>>(emptyList())
    val envVarsState: StateFlow<List<EnvVarEntry>> = _envVarsState.asStateFlow()

    init {
        loadCurrentTools()
        loadEnvVars()
    }

    /**
     * Load currently registered JS tools (from ToolRegistry).
     */
    private fun loadCurrentTools() {
        val jsToolInfos = toolRegistry.getAllToolNames()
            .mapNotNull { name -> toolRegistry.getTool(name) as? JsTool }
            .map { tool ->
                JsToolInfo(
                    name = tool.definition.name,
                    description = tool.definition.description,
                    filePath = tool.jsFilePath,
                    timeoutSeconds = tool.definition.timeoutSeconds
                )
            }
        _jsToolsState.update { it.copy(tools = jsToolInfos) }
    }

    /**
     * Reload JS tools from the file system.
     * Unregisters existing JS tools and re-scans tool directories.
     */
    fun reloadJsTools() {
        viewModelScope.launch {
            _jsToolsState.update { it.copy(isReloading = true) }

            withContext(Dispatchers.IO) {
                // Unregister existing JS tools from registry
                toolRegistry.unregisterByType<JsTool>()

                // Reload from file system
                val loadResult = jsToolLoader.loadTools()
                val conflicts = jsToolLoader.registerTools(toolRegistry, loadResult.loadedTools)

                val allErrors = loadResult.errors + conflicts
                val toolInfos = loadResult.loadedTools.map { tool ->
                    JsToolInfo(
                        name = tool.definition.name,
                        description = tool.definition.description,
                        filePath = tool.jsFilePath,
                        timeoutSeconds = tool.definition.timeoutSeconds
                    )
                }

                _jsToolsState.update {
                    JsToolsUiState(
                        tools = toolInfos,
                        errors = allErrors,
                        isReloading = false
                    )
                }
            }
        }
    }

    fun loadEnvVars() {
        viewModelScope.launch(Dispatchers.IO) {
            val entries = envVarStore.getKeys().map { key ->
                val value = envVarStore.get(key) ?: ""
                EnvVarEntry(
                    key = key,
                    maskedValue = maskValue(value)
                )
            }
            _envVarsState.value = entries
        }
    }

    fun addEnvVar(key: String, value: String) {
        viewModelScope.launch(Dispatchers.IO) {
            envVarStore.set(key, value)
            loadEnvVars()
        }
    }

    fun deleteEnvVar(key: String) {
        viewModelScope.launch(Dispatchers.IO) {
            envVarStore.delete(key)
            loadEnvVars()
        }
    }

    private fun maskValue(value: String): String {
        if (value.length <= 8) return "****"
        return "${value.take(3)}...${value.takeLast(4)}"
    }
}
