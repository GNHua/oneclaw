package com.oneclaw.shadow.feature.provider

import androidx.lifecycle.SavedStateHandle
import com.oneclaw.shadow.core.model.AiModel
import com.oneclaw.shadow.core.model.ConnectionErrorType
import com.oneclaw.shadow.core.model.ConnectionTestResult
import com.oneclaw.shadow.core.model.ModelSource
import com.oneclaw.shadow.core.model.Provider
import com.oneclaw.shadow.core.model.ProviderType
import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.ErrorCode
import com.oneclaw.shadow.data.security.ApiKeyStorage
import com.oneclaw.shadow.feature.provider.usecase.FetchModelsUseCase
import com.oneclaw.shadow.feature.provider.usecase.SetDefaultModelUseCase
import com.oneclaw.shadow.feature.provider.usecase.TestConnectionUseCase
import com.oneclaw.shadow.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherRule::class)
class ProviderDetailViewModelTest {

    private lateinit var providerRepository: ProviderRepository
    private lateinit var apiKeyStorage: ApiKeyStorage
    private lateinit var testConnectionUseCase: TestConnectionUseCase
    private lateinit var fetchModelsUseCase: FetchModelsUseCase
    private lateinit var setDefaultModelUseCase: SetDefaultModelUseCase

    private val providerId = "provider-openai"
    private val now = 1000L

    private val provider = Provider(
        id = providerId,
        name = "OpenAI",
        type = ProviderType.OPENAI,
        apiBaseUrl = "https://api.openai.com/v1",
        isPreConfigured = true,
        isActive = true,
        createdAt = now,
        updatedAt = now
    )

    private val presetModel = AiModel(
        id = "gpt-4o",
        displayName = "GPT-4o",
        providerId = providerId,
        isDefault = false,
        source = ModelSource.PRESET
    )

    private val manualModel = AiModel(
        id = "gpt-custom",
        displayName = "Custom GPT",
        providerId = providerId,
        isDefault = false,
        source = ModelSource.MANUAL
    )

    private val defaultModel = AiModel(
        id = "gpt-4o",
        displayName = "GPT-4o",
        providerId = providerId,
        isDefault = true,
        source = ModelSource.PRESET
    )

    @BeforeEach
    fun setup() {
        providerRepository = mockk()
        apiKeyStorage = mockk()
        testConnectionUseCase = mockk()
        fetchModelsUseCase = mockk()
        setDefaultModelUseCase = mockk()

        every { providerRepository.getGlobalDefaultModel() } returns flowOf(null)
    }

    private fun buildViewModel(): ProviderDetailViewModel {
        val savedStateHandle = SavedStateHandle(mapOf("providerId" to providerId))
        return ProviderDetailViewModel(
            providerRepository = providerRepository,
            apiKeyStorage = apiKeyStorage,
            testConnectionUseCase = testConnectionUseCase,
            fetchModelsUseCase = fetchModelsUseCase,
            setDefaultModelUseCase = setDefaultModelUseCase,
            savedStateHandle = savedStateHandle
        )
    }

    // --- Initial load ---

    @Test
    fun `initial load populates provider, models, and masked API key`() = runTest {
        coEvery { providerRepository.getProviderById(providerId) } returns provider
        coEvery { providerRepository.getModelsForProvider(providerId) } returns listOf(presetModel)
        every { apiKeyStorage.getApiKey(providerId) } returns "sk-abcdefgh1234"

        val viewModel = buildViewModel()
        val state = viewModel.uiState.value

        assertEquals(provider, state.provider)
        assertEquals(1, state.models.size)
        assertFalse(state.isLoading)
        // Key should be masked, not shown in full
        assertTrue(state.apiKeyMasked.contains("...."))
        assertEquals("sk-abcdefgh1234", state.apiKeyFull)
    }

    @Test
    fun `initial load with no API key shows empty masked key`() = runTest {
        coEvery { providerRepository.getProviderById(providerId) } returns provider
        coEvery { providerRepository.getModelsForProvider(providerId) } returns emptyList()
        every { apiKeyStorage.getApiKey(providerId) } returns null

        val viewModel = buildViewModel()
        val state = viewModel.uiState.value

        assertEquals("", state.apiKeyMasked)
        assertEquals("", state.apiKeyFull)
    }

    @Test
    fun `global default model is reflected in state`() = runTest {
        every { providerRepository.getGlobalDefaultModel() } returns flowOf(defaultModel)
        coEvery { providerRepository.getProviderById(providerId) } returns provider
        coEvery { providerRepository.getModelsForProvider(providerId) } returns listOf(defaultModel)
        every { apiKeyStorage.getApiKey(providerId) } returns null

        val viewModel = buildViewModel()
        val state = viewModel.uiState.value

        assertEquals("gpt-4o", state.globalDefaultModelId)
        assertEquals(providerId, state.globalDefaultProviderId)
    }

    // --- saveApiKey ---

    @Test
    fun `saveApiKey stores the key and updates masked display`() = runTest {
        coEvery { providerRepository.getProviderById(providerId) } returns provider
        coEvery { providerRepository.getModelsForProvider(providerId) } returns emptyList()
        every { apiKeyStorage.getApiKey(providerId) } returns null
        every { apiKeyStorage.setApiKey(providerId, any()) } returns Unit

        val viewModel = buildViewModel()
        viewModel.saveApiKey("sk-newkey9999")

        val state = viewModel.uiState.value
        coVerify { apiKeyStorage.setApiKey(providerId, "sk-newkey9999") }
        assertEquals("sk-newkey9999", state.apiKeyFull)
        assertFalse(state.isEditingApiKey)
        assertEquals("", state.apiKeyInput)
        assertEquals("API key saved.", state.successMessage)
    }

    // --- toggleApiKeyVisibility ---

    @Test
    fun `toggleApiKeyVisibility flips apiKeyVisible flag`() = runTest {
        coEvery { providerRepository.getProviderById(providerId) } returns provider
        coEvery { providerRepository.getModelsForProvider(providerId) } returns emptyList()
        every { apiKeyStorage.getApiKey(providerId) } returns null

        val viewModel = buildViewModel()
        assertFalse(viewModel.uiState.value.apiKeyVisible)

        viewModel.toggleApiKeyVisibility()
        assertTrue(viewModel.uiState.value.apiKeyVisible)

        viewModel.toggleApiKeyVisibility()
        assertFalse(viewModel.uiState.value.apiKeyVisible)
    }

    // --- testConnection ---

    @Test
    fun `testConnection success updates connectionTestResult and triggers refreshModels`() = runTest {
        val successResult = ConnectionTestResult(
            success = true,
            modelCount = 5,
            errorType = null,
            errorMessage = null
        )
        coEvery { providerRepository.getProviderById(providerId) } returns provider
        coEvery { providerRepository.getModelsForProvider(providerId) } returns listOf(presetModel)
        every { apiKeyStorage.getApiKey(providerId) } returns "sk-key"
        coEvery { testConnectionUseCase(providerId) } returns AppResult.Success(successResult)
        coEvery { fetchModelsUseCase(providerId) } returns AppResult.Success(listOf(presetModel))

        val viewModel = buildViewModel()
        viewModel.testConnection()

        val state = viewModel.uiState.value
        assertFalse(state.isTestingConnection)
        assertNotNull(state.connectionTestResult)
        assertTrue(state.connectionTestResult!!.success)
        // refreshModels was triggered — fetchModelsUseCase should have been called
        coVerify { fetchModelsUseCase(providerId) }
    }

    @Test
    fun `testConnection failure sets error message`() = runTest {
        coEvery { providerRepository.getProviderById(providerId) } returns provider
        coEvery { providerRepository.getModelsForProvider(providerId) } returns emptyList()
        every { apiKeyStorage.getApiKey(providerId) } returns null
        coEvery { testConnectionUseCase(providerId) } returns AppResult.Error(
            message = "Network error",
            code = ErrorCode.NETWORK_ERROR
        )

        val viewModel = buildViewModel()
        viewModel.testConnection()

        val state = viewModel.uiState.value
        assertFalse(state.isTestingConnection)
        assertEquals("Network error", state.errorMessage)
    }

    // --- refreshModels ---

    @Test
    fun `refreshModels success updates models list`() = runTest {
        val refreshedModels = listOf(presetModel, manualModel)
        coEvery { providerRepository.getProviderById(providerId) } returns provider
        coEvery { providerRepository.getModelsForProvider(providerId) } returns listOf(presetModel)
        every { apiKeyStorage.getApiKey(providerId) } returns "sk-key"
        coEvery { fetchModelsUseCase(providerId) } returns AppResult.Success(refreshedModels)

        val viewModel = buildViewModel()
        viewModel.refreshModels()

        val state = viewModel.uiState.value
        assertFalse(state.isRefreshingModels)
        assertEquals(2, state.models.size)
    }

    @Test
    fun `refreshModels failure sets error message`() = runTest {
        coEvery { providerRepository.getProviderById(providerId) } returns provider
        coEvery { providerRepository.getModelsForProvider(providerId) } returns emptyList()
        every { apiKeyStorage.getApiKey(providerId) } returns null
        coEvery { fetchModelsUseCase(providerId) } returns AppResult.Error(
            message = "Fetch failed",
            code = ErrorCode.NETWORK_ERROR
        )

        val viewModel = buildViewModel()
        viewModel.refreshModels()

        val state = viewModel.uiState.value
        assertFalse(state.isRefreshingModels)
        assertEquals("Fetch failed", state.errorMessage)
    }

    // --- setDefaultModel ---

    @Test
    fun `setDefaultModel success sets successMessage`() = runTest {
        coEvery { providerRepository.getProviderById(providerId) } returns provider
        coEvery { providerRepository.getModelsForProvider(providerId) } returns listOf(presetModel)
        every { apiKeyStorage.getApiKey(providerId) } returns null
        coEvery { setDefaultModelUseCase("gpt-4o", providerId) } returns AppResult.Success(Unit)

        val viewModel = buildViewModel()
        viewModel.setDefaultModel("gpt-4o")

        assertEquals("Default model set.", viewModel.uiState.value.successMessage)
    }

    @Test
    fun `setDefaultModel failure sets errorMessage`() = runTest {
        coEvery { providerRepository.getProviderById(providerId) } returns provider
        coEvery { providerRepository.getModelsForProvider(providerId) } returns listOf(presetModel)
        every { apiKeyStorage.getApiKey(providerId) } returns null
        coEvery { setDefaultModelUseCase("gpt-4o", providerId) } returns AppResult.Error(
            message = "Model not found",
            code = ErrorCode.VALIDATION_ERROR
        )

        val viewModel = buildViewModel()
        viewModel.setDefaultModel("gpt-4o")

        assertEquals("Model not found", viewModel.uiState.value.errorMessage)
    }

    // --- toggleProviderActive ---

    @Test
    fun `toggleProviderActive flips isActive and calls repository`() = runTest {
        coEvery { providerRepository.getProviderById(providerId) } returns provider
        coEvery { providerRepository.getModelsForProvider(providerId) } returns emptyList()
        every { apiKeyStorage.getApiKey(providerId) } returns null
        coEvery { providerRepository.setProviderActive(providerId, false) } returns Unit

        val viewModel = buildViewModel()
        // provider.isActive == true, so toggle → false
        viewModel.toggleProviderActive()

        coVerify { providerRepository.setProviderActive(providerId, false) }
        assertFalse(viewModel.uiState.value.isActive)
    }

    // --- addManualModel ---

    @Test
    fun `addManualModel success updates models and shows success message`() = runTest {
        coEvery { providerRepository.getProviderById(providerId) } returns provider
        coEvery { providerRepository.getModelsForProvider(providerId) } returnsMany listOf(
            listOf(presetModel),        // initial load
            listOf(presetModel, manualModel) // after add
        )
        every { apiKeyStorage.getApiKey(providerId) } returns null
        coEvery {
            providerRepository.addManualModel(providerId, "gpt-custom", "Custom GPT")
        } returns AppResult.Success(Unit)

        val viewModel = buildViewModel()
        viewModel.addManualModel("gpt-custom", "Custom GPT")

        val state = viewModel.uiState.value
        assertEquals(2, state.models.size)
        assertFalse(state.showAddModelDialog)
        assertEquals("Model added.", state.successMessage)
    }

    @Test
    fun `addManualModel duplicate sets errorMessage`() = runTest {
        coEvery { providerRepository.getProviderById(providerId) } returns provider
        coEvery { providerRepository.getModelsForProvider(providerId) } returns listOf(presetModel)
        every { apiKeyStorage.getApiKey(providerId) } returns null
        coEvery {
            providerRepository.addManualModel(providerId, "gpt-4o", null)
        } returns AppResult.Error(
            message = "Model already exists",
            code = ErrorCode.VALIDATION_ERROR
        )

        val viewModel = buildViewModel()
        viewModel.addManualModel("gpt-4o", null)

        assertEquals("Model already exists", viewModel.uiState.value.errorMessage)
    }

    // --- deleteManualModel ---

    @Test
    fun `deleteManualModel success refreshes model list`() = runTest {
        coEvery { providerRepository.getProviderById(providerId) } returns provider
        coEvery { providerRepository.getModelsForProvider(providerId) } returnsMany listOf(
            listOf(presetModel, manualModel), // initial load
            listOf(presetModel)               // after delete
        )
        every { apiKeyStorage.getApiKey(providerId) } returns null
        coEvery {
            providerRepository.deleteManualModel(providerId, "gpt-custom")
        } returns AppResult.Success(Unit)

        val viewModel = buildViewModel()
        viewModel.deleteManualModel("gpt-custom")

        val state = viewModel.uiState.value
        assertEquals(1, state.models.size)
        assertEquals("gpt-4o", state.models.first().id)
    }

    @Test
    fun `deleteManualModel not-found sets errorMessage`() = runTest {
        coEvery { providerRepository.getProviderById(providerId) } returns provider
        coEvery { providerRepository.getModelsForProvider(providerId) } returns listOf(presetModel)
        every { apiKeyStorage.getApiKey(providerId) } returns null
        coEvery {
            providerRepository.deleteManualModel(providerId, "nonexistent")
        } returns AppResult.Error(
            message = "Model not found",
            code = ErrorCode.VALIDATION_ERROR
        )

        val viewModel = buildViewModel()
        viewModel.deleteManualModel("nonexistent")

        assertEquals("Model not found", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `deleteManualModel on non-manual model sets errorMessage`() = runTest {
        coEvery { providerRepository.getProviderById(providerId) } returns provider
        coEvery { providerRepository.getModelsForProvider(providerId) } returns listOf(presetModel)
        every { apiKeyStorage.getApiKey(providerId) } returns null
        coEvery {
            providerRepository.deleteManualModel(providerId, "gpt-4o")
        } returns AppResult.Error(
            message = "Cannot delete a preset model",
            code = ErrorCode.VALIDATION_ERROR
        )

        val viewModel = buildViewModel()
        viewModel.deleteManualModel("gpt-4o")

        assertEquals("Cannot delete a preset model", viewModel.uiState.value.errorMessage)
    }

    // --- deleteProvider ---

    @Test
    fun `deleteProvider success sets successMessage`() = runTest {
        coEvery { providerRepository.getProviderById(providerId) } returns provider
        coEvery { providerRepository.getModelsForProvider(providerId) } returns emptyList()
        every { apiKeyStorage.getApiKey(providerId) } returns null
        coEvery { providerRepository.deleteProvider(providerId) } returns AppResult.Success(Unit)

        val viewModel = buildViewModel()
        viewModel.deleteProvider()

        assertEquals("Provider deleted.", viewModel.uiState.value.successMessage)
    }

    @Test
    fun `deleteProvider pre-configured provider sets errorMessage`() = runTest {
        coEvery { providerRepository.getProviderById(providerId) } returns provider
        coEvery { providerRepository.getModelsForProvider(providerId) } returns emptyList()
        every { apiKeyStorage.getApiKey(providerId) } returns null
        coEvery { providerRepository.deleteProvider(providerId) } returns AppResult.Error(
            message = "Cannot delete a pre-configured provider",
            code = ErrorCode.VALIDATION_ERROR
        )

        val viewModel = buildViewModel()
        viewModel.deleteProvider()

        assertEquals("Cannot delete a pre-configured provider", viewModel.uiState.value.errorMessage)
    }

    // --- clearError / clearSuccess ---

    @Test
    fun `clearError clears errorMessage`() = runTest {
        coEvery { providerRepository.getProviderById(providerId) } returns provider
        coEvery { providerRepository.getModelsForProvider(providerId) } returns emptyList()
        every { apiKeyStorage.getApiKey(providerId) } returns null
        coEvery { testConnectionUseCase(providerId) } returns AppResult.Error(
            message = "Some error",
            code = ErrorCode.NETWORK_ERROR
        )

        val viewModel = buildViewModel()
        viewModel.testConnection()
        assertNotNull(viewModel.uiState.value.errorMessage)

        viewModel.clearError()
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `clearSuccess clears successMessage`() = runTest {
        coEvery { providerRepository.getProviderById(providerId) } returns provider
        coEvery { providerRepository.getModelsForProvider(providerId) } returns emptyList()
        every { apiKeyStorage.getApiKey(providerId) } returns null
        every { apiKeyStorage.setApiKey(providerId, any()) } returns Unit

        val viewModel = buildViewModel()
        viewModel.saveApiKey("sk-key1234")
        assertNotNull(viewModel.uiState.value.successMessage)

        viewModel.clearSuccess()
        assertNull(viewModel.uiState.value.successMessage)
    }
}
