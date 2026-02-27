package com.oneclaw.shadow.feature.provider.usecase

import com.oneclaw.shadow.core.model.AiModel
import com.oneclaw.shadow.core.model.ModelSource
import com.oneclaw.shadow.core.model.Provider
import com.oneclaw.shadow.core.model.ProviderType
import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.ErrorCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SetDefaultModelUseCaseTest {

    private lateinit var providerRepository: ProviderRepository
    private lateinit var useCase: SetDefaultModelUseCase

    private val now = System.currentTimeMillis()

    private val activeProvider = Provider(
        id = "provider-openai",
        name = "OpenAI",
        type = ProviderType.OPENAI,
        apiBaseUrl = "https://api.openai.com/v1",
        isPreConfigured = true,
        isActive = true,
        createdAt = now,
        updatedAt = now
    )

    private val inactiveProvider = activeProvider.copy(isActive = false)

    private val models = listOf(
        AiModel("gpt-4o", "GPT-4o", "provider-openai", false, ModelSource.PRESET),
        AiModel("gpt-4o-mini", "GPT-4o Mini", "provider-openai", false, ModelSource.PRESET)
    )

    @BeforeEach
    fun setup() {
        providerRepository = mockk()
        useCase = SetDefaultModelUseCase(providerRepository)
    }

    @Test
    fun `invoke succeeds when model exists and provider is active`() = runTest {
        coEvery { providerRepository.getModelsForProvider("provider-openai") } returns models
        coEvery { providerRepository.getProviderById("provider-openai") } returns activeProvider
        coEvery { providerRepository.setGlobalDefaultModel("gpt-4o", "provider-openai") } returns Unit

        val result = useCase("gpt-4o", "provider-openai")

        assertTrue(result is AppResult.Success)
        coVerify { providerRepository.setGlobalDefaultModel("gpt-4o", "provider-openai") }
    }

    @Test
    fun `invoke returns error when model not found`() = runTest {
        coEvery { providerRepository.getModelsForProvider("provider-openai") } returns models

        val result = useCase("nonexistent-model", "provider-openai")

        assertTrue(result is AppResult.Error)
        assertEquals(ErrorCode.VALIDATION_ERROR, (result as AppResult.Error).code)
    }

    @Test
    fun `invoke returns error when provider not found`() = runTest {
        coEvery { providerRepository.getModelsForProvider("provider-openai") } returns models
        coEvery { providerRepository.getProviderById("provider-openai") } returns null

        val result = useCase("gpt-4o", "provider-openai")

        assertTrue(result is AppResult.Error)
        assertEquals(ErrorCode.VALIDATION_ERROR, (result as AppResult.Error).code)
    }

    @Test
    fun `invoke returns error when provider is inactive`() = runTest {
        coEvery { providerRepository.getModelsForProvider("provider-openai") } returns models
        coEvery { providerRepository.getProviderById("provider-openai") } returns inactiveProvider

        val result = useCase("gpt-4o", "provider-openai")

        assertTrue(result is AppResult.Error)
        assertEquals(ErrorCode.VALIDATION_ERROR, (result as AppResult.Error).code)
    }
}
