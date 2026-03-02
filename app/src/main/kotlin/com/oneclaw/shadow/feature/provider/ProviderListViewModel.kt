package com.oneclaw.shadow.feature.provider

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.data.security.ApiKeyStorage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class ProviderListViewModel(
    private val providerRepository: ProviderRepository,
    private val apiKeyStorage: ApiKeyStorage
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProviderListUiState())
    val uiState: StateFlow<ProviderListUiState> = _uiState.asStateFlow()

    init {
        loadProviders()
    }

    private fun loadProviders() {
        viewModelScope.launch {
            providerRepository.getAllProviders().collect { providers ->
                val items = providers.map { provider ->
                    val hasKey = apiKeyStorage.hasApiKey(provider.id)
                    val models = providerRepository.getModelsForProvider(provider.id)
                    ProviderListItem(
                        id = provider.id,
                        name = provider.name,
                        type = provider.type,
                        modelCount = models.size,
                        isActive = provider.isActive,
                        isPreConfigured = provider.isPreConfigured,
                        hasApiKey = hasKey,
                        connectionStatus = if (!hasKey) ConnectionStatus.NOT_CONFIGURED
                                          else ConnectionStatus.DISCONNECTED
                    )
                }
                _uiState.update { it.copy(providers = items, isLoading = false) }
            }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val updated = _uiState.value.providers.map { item ->
                val hasKey = apiKeyStorage.hasApiKey(item.id)
                val models = providerRepository.getModelsForProvider(item.id)
                item.copy(
                    modelCount = models.size,
                    hasApiKey = hasKey,
                    connectionStatus = if (!hasKey) ConnectionStatus.NOT_CONFIGURED
                                      else ConnectionStatus.DISCONNECTED
                )
            }
            _uiState.update { it.copy(providers = updated) }
        }
    }
}
