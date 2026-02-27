package com.oneclaw.shadow.feature.provider

import com.oneclaw.shadow.core.model.AiModel
import com.oneclaw.shadow.core.model.ConnectionTestResult
import com.oneclaw.shadow.core.model.Provider
import com.oneclaw.shadow.core.model.ProviderType

// --- Provider List ---

data class ProviderListUiState(
    val providers: List<ProviderListItem> = emptyList(),
    val isLoading: Boolean = true
)

data class ProviderListItem(
    val id: String,
    val name: String,
    val type: ProviderType,
    val modelCount: Int,
    val isActive: Boolean,
    val isPreConfigured: Boolean,
    val hasApiKey: Boolean,
    val connectionStatus: ConnectionStatus
)

enum class ConnectionStatus {
    CONNECTED,
    NOT_CONFIGURED,
    DISCONNECTED
}

// --- Provider Detail ---

data class ProviderDetailUiState(
    val provider: Provider? = null,
    val models: List<AiModel> = emptyList(),
    val globalDefaultModelId: String? = null,
    val globalDefaultProviderId: String? = null,

    val apiKeyMasked: String = "",
    val apiKeyVisible: Boolean = false,
    val apiKeyFull: String = "",
    val isEditingApiKey: Boolean = false,
    val apiKeyInput: String = "",

    val isTestingConnection: Boolean = false,
    val connectionTestResult: ConnectionTestResult? = null,

    val isRefreshingModels: Boolean = false,

    val isActive: Boolean = true,
    val isPreConfigured: Boolean = false,

    val showAddModelDialog: Boolean = false,
    val manualModelIdInput: String = "",

    val isLoading: Boolean = true,
    val errorMessage: String? = null,
    val successMessage: String? = null
)

// --- Setup ---

data class SetupUiState(
    val step: SetupStep = SetupStep.CHOOSE_PROVIDER,
    val selectedProviderType: ProviderType? = null,
    val selectedProviderId: String? = null,
    val apiKeyInput: String = "",
    val isTestingConnection: Boolean = false,
    val connectionTestResult: ConnectionTestResult? = null,
    val models: List<AiModel> = emptyList(),
    val selectedDefaultModelId: String? = null,
    val errorMessage: String? = null
)

enum class SetupStep {
    CHOOSE_PROVIDER,
    ENTER_API_KEY,
    SELECT_MODEL
}
