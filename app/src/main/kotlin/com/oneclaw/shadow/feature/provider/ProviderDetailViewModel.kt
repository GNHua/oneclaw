package com.oneclaw.shadow.feature.provider

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneclaw.shadow.core.repository.ProviderRepository
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

class ProviderDetailViewModel(
    private val providerRepository: ProviderRepository,
    private val apiKeyStorage: ApiKeyStorage,
    private val testConnectionUseCase: TestConnectionUseCase,
    private val fetchModelsUseCase: FetchModelsUseCase,
    private val setDefaultModelUseCase: SetDefaultModelUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val providerId: String = savedStateHandle["providerId"]
        ?: throw IllegalArgumentException("providerId is required")

    private val _uiState = MutableStateFlow(ProviderDetailUiState())
    val uiState: StateFlow<ProviderDetailUiState> = _uiState.asStateFlow()

    init {
        loadProvider()
        observeDefaultModel()
    }

    private fun loadProvider() {
        viewModelScope.launch {
            val provider = providerRepository.getProviderById(providerId) ?: return@launch
            val models = providerRepository.getModelsForProvider(providerId)
            val apiKey = apiKeyStorage.getApiKey(providerId)
            val masked = maskApiKey(apiKey)

            _uiState.update {
                it.copy(
                    provider = provider,
                    models = models,
                    apiKeyMasked = masked,
                    apiKeyFull = apiKey ?: "",
                    isActive = provider.isActive,
                    isPreConfigured = provider.isPreConfigured,
                    isLoading = false
                )
            }
        }
    }

    private fun observeDefaultModel() {
        viewModelScope.launch {
            providerRepository.getGlobalDefaultModel().collect { defaultModel ->
                _uiState.update {
                    it.copy(
                        globalDefaultModelId = defaultModel?.id,
                        globalDefaultProviderId = defaultModel?.providerId
                    )
                }
            }
        }
    }

    fun saveApiKey(apiKey: String) {
        viewModelScope.launch {
            apiKeyStorage.setApiKey(providerId, apiKey)
            _uiState.update {
                it.copy(
                    apiKeyMasked = maskApiKey(apiKey),
                    apiKeyFull = apiKey,
                    isEditingApiKey = false,
                    apiKeyInput = "",
                    successMessage = "API key saved."
                )
            }
        }
    }

    fun toggleApiKeyVisibility() {
        _uiState.update { it.copy(apiKeyVisible = !it.apiKeyVisible) }
    }

    fun startEditingApiKey() {
        _uiState.update { it.copy(isEditingApiKey = true, apiKeyInput = "") }
    }

    fun cancelEditingApiKey() {
        _uiState.update { it.copy(isEditingApiKey = false, apiKeyInput = "") }
    }

    fun onApiKeyInputChange(value: String) {
        _uiState.update { it.copy(apiKeyInput = value) }
    }

    fun testConnection() {
        viewModelScope.launch {
            _uiState.update { it.copy(isTestingConnection = true, connectionTestResult = null) }

            when (val result = testConnectionUseCase(providerId)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isTestingConnection = false,
                            connectionTestResult = result.data
                        )
                    }
                    if (result.data.success) {
                        refreshModels()
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isTestingConnection = false,
                            errorMessage = result.message
                        )
                    }
                }
            }
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshingModels = true) }

            when (val result = fetchModelsUseCase(providerId)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            models = result.data,
                            isRefreshingModels = false
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isRefreshingModels = false,
                            errorMessage = result.message
                        )
                    }
                }
            }
        }
    }

    fun setDefaultModel(modelId: String) {
        viewModelScope.launch {
            when (val result = setDefaultModelUseCase(modelId, providerId)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(successMessage = "Default model set.") }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(errorMessage = result.message) }
                }
            }
        }
    }

    fun toggleProviderActive() {
        viewModelScope.launch {
            val newActive = !(_uiState.value.isActive)
            providerRepository.setProviderActive(providerId, newActive)
            _uiState.update { it.copy(isActive = newActive) }
        }
    }

    fun showAddModelDialog() {
        _uiState.update { it.copy(showAddModelDialog = true, manualModelIdInput = "") }
    }

    fun hideAddModelDialog() {
        _uiState.update { it.copy(showAddModelDialog = false, manualModelIdInput = "") }
    }

    fun onManualModelIdChange(value: String) {
        _uiState.update { it.copy(manualModelIdInput = value) }
    }

    fun addManualModel(modelId: String, displayName: String?) {
        viewModelScope.launch {
            when (val result = providerRepository.addManualModel(providerId, modelId, displayName)) {
                is AppResult.Success -> {
                    val updatedModels = providerRepository.getModelsForProvider(providerId)
                    _uiState.update {
                        it.copy(
                            models = updatedModels,
                            showAddModelDialog = false,
                            manualModelIdInput = "",
                            successMessage = "Model added."
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(errorMessage = result.message) }
                }
            }
        }
    }

    fun deleteManualModel(modelId: String) {
        viewModelScope.launch {
            when (val result = providerRepository.deleteManualModel(providerId, modelId)) {
                is AppResult.Success -> {
                    val updatedModels = providerRepository.getModelsForProvider(providerId)
                    _uiState.update { it.copy(models = updatedModels) }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(errorMessage = result.message) }
                }
            }
        }
    }

    fun deleteProvider() {
        viewModelScope.launch {
            when (val result = providerRepository.deleteProvider(providerId)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(successMessage = "Provider deleted.") }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(errorMessage = result.message) }
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearSuccess() {
        _uiState.update { it.copy(successMessage = null) }
    }

    private fun maskApiKey(apiKey: String?): String {
        if (apiKey.isNullOrEmpty()) return ""
        if (apiKey.length <= 8) return "****${apiKey.takeLast(4)}"
        val prefix = apiKey.take(3)
        val suffix = apiKey.takeLast(4)
        return "$prefix....$suffix"
    }
}
