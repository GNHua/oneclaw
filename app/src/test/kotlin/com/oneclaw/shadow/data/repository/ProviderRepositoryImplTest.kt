package com.oneclaw.shadow.data.repository

import com.oneclaw.shadow.core.model.AiModel
import com.oneclaw.shadow.core.model.ModelSource
import com.oneclaw.shadow.core.model.Provider
import com.oneclaw.shadow.core.model.ProviderType
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.ErrorCode
import com.oneclaw.shadow.data.local.dao.ModelDao
import com.oneclaw.shadow.data.local.dao.ProviderDao
import com.oneclaw.shadow.data.local.entity.ModelEntity
import com.oneclaw.shadow.data.local.entity.ProviderEntity
import com.oneclaw.shadow.data.remote.adapter.ModelApiAdapter
import com.oneclaw.shadow.data.remote.adapter.ModelApiAdapterFactory
import com.oneclaw.shadow.data.security.ApiKeyStorage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ProviderRepositoryImplTest {

    private lateinit var providerDao: ProviderDao
    private lateinit var modelDao: ModelDao
    private lateinit var apiKeyStorage: ApiKeyStorage
    private lateinit var adapterFactory: ModelApiAdapterFactory
    private lateinit var adapter: ModelApiAdapter
    private lateinit var repository: ProviderRepositoryImpl

    private val now = 1000L

    private val openAiEntity = ProviderEntity(
        id = "provider-openai",
        name = "OpenAI",
        type = "OPENAI",
        apiBaseUrl = "https://api.openai.com/v1",
        isPreConfigured = true,
        isActive = true,
        createdAt = now,
        updatedAt = now
    )

    private val customProviderEntity = ProviderEntity(
        id = "provider-custom",
        name = "Custom",
        type = "OPENAI",
        apiBaseUrl = "http://localhost:11434/v1",
        isPreConfigured = false,
        isActive = true,
        createdAt = now,
        updatedAt = now
    )

    private val defaultModelEntity = ModelEntity(
        id = "gpt-4o",
        displayName = "GPT-4o",
        providerId = "provider-openai",
        isDefault = true,
        source = "PRESET"
    )

    private val manualModelEntity = ModelEntity(
        id = "llama3",
        displayName = "Llama 3",
        providerId = "provider-custom",
        isDefault = false,
        source = "MANUAL"
    )

    @BeforeEach
    fun setup() {
        providerDao = mockk(relaxed = true)
        modelDao = mockk(relaxed = true)
        apiKeyStorage = mockk(relaxed = true)
        adapterFactory = mockk()
        adapter = mockk()

        repository = ProviderRepositoryImpl(providerDao, modelDao, apiKeyStorage, adapterFactory)
    }

    // --- deleteProvider ---

    @Test
    fun `deleteProvider blocks deletion when provider has global default model`() = runTest {
        every { modelDao.getDefaultModel() } returns flowOf(defaultModelEntity)

        val result = repository.deleteProvider("provider-openai")

        assertTrue(result is AppResult.Error)
        assertEquals(ErrorCode.VALIDATION_ERROR, (result as AppResult.Error).code)
        coVerify(exactly = 0) { providerDao.deleteCustomProvider(any()) }
    }

    @Test
    fun `deleteProvider deletes custom provider and API key when no default conflict`() = runTest {
        every { modelDao.getDefaultModel() } returns flowOf(null)
        coEvery { providerDao.deleteCustomProvider("provider-custom") } returns 1

        val result = repository.deleteProvider("provider-custom")

        assertTrue(result is AppResult.Success)
        coVerify { apiKeyStorage.deleteApiKey("provider-custom") }
        coVerify { providerDao.deleteCustomProvider("provider-custom") }
    }

    @Test
    fun `deleteProvider returns error when trying to delete pre-configured provider`() = runTest {
        every { modelDao.getDefaultModel() } returns flowOf(null)
        coEvery { providerDao.deleteCustomProvider("provider-openai") } returns 0  // pre-configured, 0 rows deleted

        val result = repository.deleteProvider("provider-openai")

        assertTrue(result is AppResult.Error)
        assertEquals(ErrorCode.VALIDATION_ERROR, (result as AppResult.Error).code)
    }

    // --- addManualModel ---

    @Test
    fun `addManualModel succeeds when model does not exist`() = runTest {
        coEvery { modelDao.getModel("new-model", "provider-custom") } returns null

        val result = repository.addManualModel("provider-custom", "new-model", "New Model")

        assertTrue(result is AppResult.Success)
        coVerify { modelDao.insert(any()) }
    }

    @Test
    fun `addManualModel returns error when model already exists`() = runTest {
        coEvery { modelDao.getModel("llama3", "provider-custom") } returns manualModelEntity

        val result = repository.addManualModel("provider-custom", "llama3", "Llama 3")

        assertTrue(result is AppResult.Error)
        assertEquals(ErrorCode.VALIDATION_ERROR, (result as AppResult.Error).code)
        coVerify(exactly = 0) { modelDao.insert(any()) }
    }

    // --- deleteManualModel ---

    @Test
    fun `deleteManualModel succeeds for MANUAL source model that is not default`() = runTest {
        coEvery { modelDao.getModel("llama3", "provider-custom") } returns manualModelEntity

        val result = repository.deleteManualModel("provider-custom", "llama3")

        assertTrue(result is AppResult.Success)
        coVerify { modelDao.delete("llama3", "provider-custom") }
    }

    @Test
    fun `deleteManualModel returns error when model not found`() = runTest {
        coEvery { modelDao.getModel("nonexistent", "provider-custom") } returns null

        val result = repository.deleteManualModel("provider-custom", "nonexistent")

        assertTrue(result is AppResult.Error)
        assertEquals(ErrorCode.VALIDATION_ERROR, (result as AppResult.Error).code)
    }

    @Test
    fun `deleteManualModel returns error for non-MANUAL source`() = runTest {
        val presetModel = ModelEntity("gpt-4o", "GPT-4o", "provider-openai", false, "PRESET")
        coEvery { modelDao.getModel("gpt-4o", "provider-openai") } returns presetModel

        val result = repository.deleteManualModel("provider-openai", "gpt-4o")

        assertTrue(result is AppResult.Error)
        assertEquals(ErrorCode.VALIDATION_ERROR, (result as AppResult.Error).code)
        coVerify(exactly = 0) { modelDao.delete(any(), any()) }
    }

    @Test
    fun `deleteManualModel returns error when model is the global default`() = runTest {
        val defaultManualModel = manualModelEntity.copy(isDefault = true)
        coEvery { modelDao.getModel("llama3", "provider-custom") } returns defaultManualModel

        val result = repository.deleteManualModel("provider-custom", "llama3")

        assertTrue(result is AppResult.Error)
        assertEquals(ErrorCode.VALIDATION_ERROR, (result as AppResult.Error).code)
        coVerify(exactly = 0) { modelDao.delete(any(), any()) }
    }

    // --- fetchModelsFromApi ---

    @Test
    fun `fetchModelsFromApi returns error when provider not found`() = runTest {
        coEvery { providerDao.getProviderById("unknown") } returns null

        val result = repository.fetchModelsFromApi("unknown")

        assertTrue(result is AppResult.Error)
        assertEquals(ErrorCode.VALIDATION_ERROR, (result as AppResult.Error).code)
    }

    @Test
    fun `fetchModelsFromApi returns error when no API key`() = runTest {
        coEvery { providerDao.getProviderById("provider-openai") } returns openAiEntity
        coEvery { apiKeyStorage.getApiKey("provider-openai") } returns null

        val result = repository.fetchModelsFromApi("provider-openai")

        assertTrue(result is AppResult.Error)
        assertEquals(ErrorCode.VALIDATION_ERROR, (result as AppResult.Error).code)
    }

    @Test
    fun `fetchModelsFromApi sets providerId on returned models and saves them`() = runTest {
        val fetchedModels = listOf(
            AiModel("gpt-4o", "GPT-4o", "", false, ModelSource.DYNAMIC),  // empty providerId from adapter
            AiModel("gpt-4o-mini", "GPT-4o Mini", "", false, ModelSource.DYNAMIC)
        )
        coEvery { providerDao.getProviderById("provider-openai") } returns openAiEntity
        coEvery { apiKeyStorage.getApiKey("provider-openai") } returns "sk-test"
        every { adapterFactory.getAdapter(ProviderType.OPENAI) } returns adapter
        coEvery { adapter.listModels(any(), any()) } returns AppResult.Success(fetchedModels)

        val result = repository.fetchModelsFromApi("provider-openai")

        assertTrue(result is AppResult.Success)
        val models = (result as AppResult.Success).data
        // All returned models should have providerId set
        assertTrue(models.all { it.providerId == "provider-openai" })
        // Dynamic models should be cleared before inserting new ones
        coVerify { modelDao.deleteByProviderAndSource("provider-openai", "DYNAMIC") }
        coVerify { modelDao.insertAll(any()) }
    }

    // --- testConnection ---

    @Test
    fun `testConnection returns error when provider not found`() = runTest {
        coEvery { providerDao.getProviderById("unknown") } returns null

        val result = repository.testConnection("unknown")

        assertTrue(result is AppResult.Error)
        assertEquals(ErrorCode.VALIDATION_ERROR, (result as AppResult.Error).code)
    }

    @Test
    fun `testConnection returns error when no API key`() = runTest {
        coEvery { providerDao.getProviderById("provider-openai") } returns openAiEntity
        coEvery { apiKeyStorage.getApiKey("provider-openai") } returns null

        val result = repository.testConnection("provider-openai")

        assertTrue(result is AppResult.Error)
        assertEquals(ErrorCode.VALIDATION_ERROR, (result as AppResult.Error).code)
    }

    // --- setGlobalDefaultModel ---

    @Test
    fun `setGlobalDefaultModel clears old default then sets new`() = runTest {
        repository.setGlobalDefaultModel("gpt-4o-mini", "provider-openai")

        coVerify { modelDao.clearAllDefaults() }
        coVerify { modelDao.setDefault("gpt-4o-mini", "provider-openai") }
    }
}
