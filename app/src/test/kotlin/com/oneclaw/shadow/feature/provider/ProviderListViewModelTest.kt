package com.oneclaw.shadow.feature.provider

import com.oneclaw.shadow.core.model.AiModel
import com.oneclaw.shadow.core.model.ModelSource
import com.oneclaw.shadow.core.model.Provider
import com.oneclaw.shadow.core.model.ProviderType
import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.data.security.ApiKeyStorage
import com.oneclaw.shadow.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherRule::class)
class ProviderListViewModelTest {

    private lateinit var providerRepository: ProviderRepository
    private lateinit var apiKeyStorage: ApiKeyStorage
    private lateinit var viewModel: ProviderListViewModel

    private val now = 1000L

    private val openAiProvider = Provider(
        id = "provider-openai",
        name = "OpenAI",
        type = ProviderType.OPENAI,
        apiBaseUrl = "https://api.openai.com/v1",
        isPreConfigured = true,
        isActive = true,
        createdAt = now,
        updatedAt = now
    )

    private val anthropicProvider = Provider(
        id = "provider-anthropic",
        name = "Anthropic",
        type = ProviderType.ANTHROPIC,
        apiBaseUrl = "https://api.anthropic.com",
        isPreConfigured = true,
        isActive = true,
        createdAt = now,
        updatedAt = now
    )

    private val customProvider = Provider(
        id = "provider-custom",
        name = "Local Ollama",
        type = ProviderType.OPENAI,
        apiBaseUrl = "http://localhost:11434/v1",
        isPreConfigured = false,
        isActive = true,
        createdAt = now,
        updatedAt = now
    )

    private val presetModels = listOf(
        AiModel("gpt-4o", "GPT-4o", "provider-openai", false, ModelSource.PRESET),
        AiModel("gpt-4o-mini", "GPT-4o Mini", "provider-openai", false, ModelSource.PRESET)
    )

    @BeforeEach
    fun setup() {
        providerRepository = mockk()
        apiKeyStorage = mockk()
    }

    @Test
    fun `uiState shows isLoading true initially then false after providers load`() = runTest {
        every { providerRepository.getAllProviders() } returns flowOf(emptyList())

        viewModel = ProviderListViewModel(providerRepository, apiKeyStorage)

        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `uiState contains all providers after load`() = runTest {
        every { providerRepository.getAllProviders() } returns
            flowOf(listOf(openAiProvider, anthropicProvider, customProvider))
        coEvery { providerRepository.getModelsForProvider(any()) } returns emptyList()
        every { apiKeyStorage.hasApiKey(any()) } returns false

        viewModel = ProviderListViewModel(providerRepository, apiKeyStorage)

        assertEquals(3, viewModel.uiState.value.providers.size)
    }

    @Test
    fun `provider with no API key shows NOT_CONFIGURED status`() = runTest {
        every { providerRepository.getAllProviders() } returns flowOf(listOf(openAiProvider))
        coEvery { providerRepository.getModelsForProvider("provider-openai") } returns presetModels
        every { apiKeyStorage.hasApiKey("provider-openai") } returns false

        viewModel = ProviderListViewModel(providerRepository, apiKeyStorage)

        val item = viewModel.uiState.value.providers.first()
        assertEquals(ConnectionStatus.NOT_CONFIGURED, item.connectionStatus)
        assertFalse(item.hasApiKey)
    }

    @Test
    fun `provider with API key shows DISCONNECTED status by default`() = runTest {
        every { providerRepository.getAllProviders() } returns flowOf(listOf(openAiProvider))
        coEvery { providerRepository.getModelsForProvider("provider-openai") } returns presetModels
        every { apiKeyStorage.hasApiKey("provider-openai") } returns true

        viewModel = ProviderListViewModel(providerRepository, apiKeyStorage)

        val item = viewModel.uiState.value.providers.first()
        assertEquals(ConnectionStatus.DISCONNECTED, item.connectionStatus)
        assertTrue(item.hasApiKey)
    }

    @Test
    fun `model count is correctly populated per provider`() = runTest {
        every { providerRepository.getAllProviders() } returns flowOf(listOf(openAiProvider))
        coEvery { providerRepository.getModelsForProvider("provider-openai") } returns presetModels
        every { apiKeyStorage.hasApiKey(any()) } returns false

        viewModel = ProviderListViewModel(providerRepository, apiKeyStorage)

        val item = viewModel.uiState.value.providers.first()
        assertEquals(presetModels.size, item.modelCount)
    }

    @Test
    fun `pre-configured flag is correctly set on items`() = runTest {
        every { providerRepository.getAllProviders() } returns
            flowOf(listOf(openAiProvider, customProvider))
        coEvery { providerRepository.getModelsForProvider(any()) } returns emptyList()
        every { apiKeyStorage.hasApiKey(any()) } returns false

        viewModel = ProviderListViewModel(providerRepository, apiKeyStorage)

        val items = viewModel.uiState.value.providers
        val openAiItem = items.first { it.id == "provider-openai" }
        val customItem = items.first { it.id == "provider-custom" }

        assertTrue(openAiItem.isPreConfigured)
        assertFalse(customItem.isPreConfigured)
    }

    @Test
    fun `empty provider list shows empty state`() = runTest {
        every { providerRepository.getAllProviders() } returns flowOf(emptyList())

        viewModel = ProviderListViewModel(providerRepository, apiKeyStorage)

        assertTrue(viewModel.uiState.value.providers.isEmpty())
        assertFalse(viewModel.uiState.value.isLoading)
    }
}
