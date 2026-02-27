package com.oneclaw.shadow.feature.provider

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneclaw.shadow.core.model.ProviderType
import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.core.repository.SettingsRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.data.security.ApiKeyStorage
import com.oneclaw.shadow.feature.provider.usecase.FetchModelsUseCase
import com.oneclaw.shadow.feature.provider.usecase.SetDefaultModelUseCase
import com.oneclaw.shadow.feature.provider.usecase.TestConnectionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SetupViewModel(
    private val providerRepository: ProviderRepository,
    private val apiKeyStorage: ApiKeyStorage,
    private val testConnectionUseCase: TestConnectionUseCase,
    private val fetchModelsUseCase: FetchModelsUseCase,
    private val setDefaultModelUseCase: SetDefaultModelUseCase,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    private val preConfiguredProviderIds = mapOf(
        ProviderType.OPENAI to "provider-openai",
        ProviderType.ANTHROPIC to "provider-anthropic",
        ProviderType.GEMINI to "provider-gemini"
    )

    fun selectProvider(type: ProviderType) {
        _uiState.update {
            it.copy(
                selectedProviderType = type,
                selectedProviderId = preConfiguredProviderIds[type],
                step = SetupStep.ENTER_API_KEY,
                apiKeyInput = "",
                connectionTestResult = null,
                errorMessage = null
            )
        }
    }

    fun onApiKeyInputChange(value: String) {
        _uiState.update { it.copy(apiKeyInput = value) }
    }

    fun testConnection() {
        val state = _uiState.value
        val providerId = state.selectedProviderId ?: return
        val apiKey = state.apiKeyInput.trim()

        if (apiKey.isBlank()) {
            _uiState.update { it.copy(errorMessage = "API key cannot be empty.") }
            return
        }

        viewModelScope.launch {
            apiKeyStorage.setApiKey(providerId, apiKey)
            _uiState.update { it.copy(isTestingConnection = true, connectionTestResult = null, errorMessage = null) }

            when (val result = testConnectionUseCase(providerId)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(isTestingConnection = false, connectionTestResult = result.data) }

                    if (result.data.success) {
                        loadModels(providerId)
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(isTestingConnection = false, errorMessage = result.message)
                    }
                }
            }
        }
    }

    private suspend fun loadModels(providerId: String) {
        when (val result = fetchModelsUseCase(providerId)) {
            is AppResult.Success -> {
                _uiState.update {
                    it.copy(
                        models = result.data,
                        step = SetupStep.SELECT_MODEL
                    )
                }
            }
            is AppResult.Error -> {
                _uiState.update {
                    it.copy(
                        step = SetupStep.SELECT_MODEL,
                        errorMessage = result.message
                    )
                }
            }
        }
    }

    fun selectDefaultModel(modelId: String) {
        _uiState.update { it.copy(selectedDefaultModelId = modelId) }
    }

    fun completeSetup(onDone: () -> Unit) {
        val state = _uiState.value
        val providerId = state.selectedProviderId
        val modelId = state.selectedDefaultModelId

        viewModelScope.launch {
            if (providerId != null && modelId != null) {
                setDefaultModelUseCase(modelId, providerId)
            }
            settingsRepository.setBoolean("has_completed_setup", true)
            onDone()
        }
    }

    fun skip(onDone: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.setBoolean("has_completed_setup", true)
            onDone()
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
