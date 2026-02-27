package com.oneclaw.shadow.feature.provider.usecase

import com.oneclaw.shadow.core.model.AiModel
import com.oneclaw.shadow.core.model.ModelSource
import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.ErrorCode
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class FetchModelsUseCaseTest {

    private lateinit var providerRepository: ProviderRepository
    private lateinit var useCase: FetchModelsUseCase

    private val presetModels = listOf(
        AiModel("gpt-4o", "GPT-4o", "provider-openai", false, ModelSource.PRESET),
        AiModel("gpt-4o-mini", "GPT-4o Mini", "provider-openai", false, ModelSource.PRESET)
    )

    private val dynamicModels = listOf(
        AiModel("gpt-4o", "GPT-4o", "provider-openai", false, ModelSource.DYNAMIC),
        AiModel("gpt-4o-mini", "GPT-4o Mini", "provider-openai", false, ModelSource.DYNAMIC),
        AiModel("gpt-4-turbo", "GPT-4 Turbo", "provider-openai", false, ModelSource.DYNAMIC)
    )

    @BeforeEach
    fun setup() {
        providerRepository = mockk()
        useCase = FetchModelsUseCase(providerRepository)
    }

    @Test
    fun `invoke returns current models from DB after successful fetch`() = runTest {
        coEvery { providerRepository.fetchModelsFromApi("provider-openai") } returns
            AppResult.Success(dynamicModels)
        coEvery { providerRepository.getModelsForProvider("provider-openai") } returns
            dynamicModels + presetModels

        val result = useCase("provider-openai")

        assertTrue(result is AppResult.Success)
        val models = (result as AppResult.Success).data
        assertEquals(dynamicModels.size + presetModels.size, models.size)
    }

    @Test
    fun `invoke falls back to existing models when fetch fails`() = runTest {
        coEvery { providerRepository.fetchModelsFromApi("provider-openai") } returns
            AppResult.Error(message = "Network error", code = ErrorCode.NETWORK_ERROR)
        coEvery { providerRepository.getModelsForProvider("provider-openai") } returns
            presetModels

        val result = useCase("provider-openai")

        // Should succeed with preset models
        assertTrue(result is AppResult.Success)
        val models = (result as AppResult.Success).data
        assertEquals(presetModels.size, models.size)
    }

    @Test
    fun `invoke propagates error when fetch fails and no models exist`() = runTest {
        coEvery { providerRepository.fetchModelsFromApi("provider-openai") } returns
            AppResult.Error(message = "Network error", code = ErrorCode.NETWORK_ERROR)
        coEvery { providerRepository.getModelsForProvider("provider-openai") } returns
            emptyList()

        val result = useCase("provider-openai")

        assertTrue(result is AppResult.Error)
        assertEquals(ErrorCode.NETWORK_ERROR, (result as AppResult.Error).code)
    }
}
