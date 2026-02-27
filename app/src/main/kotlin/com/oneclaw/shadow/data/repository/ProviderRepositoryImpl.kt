package com.oneclaw.shadow.data.repository

import com.oneclaw.shadow.core.model.AiModel
import com.oneclaw.shadow.core.model.ConnectionTestResult
import com.oneclaw.shadow.core.model.ModelSource
import com.oneclaw.shadow.core.model.Provider
import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.ErrorCode
import com.oneclaw.shadow.data.local.dao.ModelDao
import com.oneclaw.shadow.data.local.dao.ProviderDao
import com.oneclaw.shadow.data.local.mapper.toDomain
import com.oneclaw.shadow.data.local.mapper.toEntity
import com.oneclaw.shadow.data.remote.adapter.ModelApiAdapterFactory
import com.oneclaw.shadow.data.security.ApiKeyStorage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class ProviderRepositoryImpl(
    private val providerDao: ProviderDao,
    private val modelDao: ModelDao,
    private val apiKeyStorage: ApiKeyStorage,
    private val adapterFactory: ModelApiAdapterFactory
) : ProviderRepository {

    override fun getAllProviders(): Flow<List<Provider>> =
        providerDao.getAllProviders().map { entities -> entities.map { it.toDomain() } }

    override fun getActiveProviders(): Flow<List<Provider>> =
        providerDao.getActiveProviders().map { entities -> entities.map { it.toDomain() } }

    override suspend fun getProviderById(id: String): Provider? =
        providerDao.getProviderById(id)?.toDomain()

    override suspend fun createProvider(provider: Provider) {
        providerDao.insert(provider.toEntity())
    }

    override suspend fun updateProvider(provider: Provider) {
        providerDao.update(provider.toEntity())
    }

    override suspend fun deleteProvider(id: String): AppResult<Unit> {
        // Block deletion if this provider has the global default model
        val defaultModel = modelDao.getDefaultModel().first()
        if (defaultModel != null && defaultModel.providerId == id) {
            return AppResult.Error(
                message = "Cannot delete the provider that has the global default model. Please set a different default model first.",
                code = ErrorCode.VALIDATION_ERROR
            )
        }

        apiKeyStorage.deleteApiKey(id)

        val deleted = providerDao.deleteCustomProvider(id)
        return if (deleted > 0) {
            AppResult.Success(Unit)
        } else {
            AppResult.Error(
                message = "Cannot delete pre-configured providers.",
                code = ErrorCode.VALIDATION_ERROR
            )
        }
    }

    override suspend fun setProviderActive(id: String, isActive: Boolean) {
        providerDao.setActive(id, isActive, System.currentTimeMillis())
    }

    override suspend fun getModelsForProvider(providerId: String): List<AiModel> =
        modelDao.getModelsForProvider(providerId).map { it.toDomain() }

    override suspend fun fetchModelsFromApi(providerId: String): AppResult<List<AiModel>> {
        val provider = providerDao.getProviderById(providerId)?.toDomain()
            ?: return AppResult.Error(message = "Provider not found", code = ErrorCode.VALIDATION_ERROR)
        val apiKey = apiKeyStorage.getApiKey(providerId)
            ?: return AppResult.Error(message = "No API key configured for this provider", code = ErrorCode.VALIDATION_ERROR)

        val adapter = adapterFactory.getAdapter(provider.type)
        return when (val result = adapter.listModels(provider.apiBaseUrl, apiKey)) {
            is AppResult.Success -> {
                val modelsWithProvider = result.data.map { it.copy(providerId = providerId) }
                modelDao.deleteByProviderAndSource(providerId, ModelSource.DYNAMIC.name)
                modelDao.insertAll(modelsWithProvider.map { it.toEntity() })
                AppResult.Success(modelsWithProvider)
            }
            is AppResult.Error -> result
        }
    }

    override suspend fun addManualModel(
        providerId: String,
        modelId: String,
        displayName: String?
    ): AppResult<Unit> {
        val existing = modelDao.getModel(modelId, providerId)
        if (existing != null) {
            return AppResult.Error(
                message = "Model '$modelId' already exists for this provider.",
                code = ErrorCode.VALIDATION_ERROR
            )
        }
        val model = AiModel(
            id = modelId,
            displayName = displayName,
            providerId = providerId,
            isDefault = false,
            source = ModelSource.MANUAL
        )
        modelDao.insert(model.toEntity())
        return AppResult.Success(Unit)
    }

    override suspend fun deleteManualModel(
        providerId: String,
        modelId: String
    ): AppResult<Unit> {
        val model = modelDao.getModel(modelId, providerId)
            ?: return AppResult.Error(message = "Model not found", code = ErrorCode.VALIDATION_ERROR)

        if (model.source != ModelSource.MANUAL.name) {
            return AppResult.Error(
                message = "Only manually added models can be deleted.",
                code = ErrorCode.VALIDATION_ERROR
            )
        }
        if (model.isDefault) {
            return AppResult.Error(
                message = "Cannot delete the global default model. Please change the default first.",
                code = ErrorCode.VALIDATION_ERROR
            )
        }
        modelDao.delete(modelId, providerId)
        return AppResult.Success(Unit)
    }

    override suspend fun testConnection(providerId: String): AppResult<ConnectionTestResult> {
        val provider = providerDao.getProviderById(providerId)?.toDomain()
            ?: return AppResult.Error(message = "Provider not found", code = ErrorCode.VALIDATION_ERROR)
        val apiKey = apiKeyStorage.getApiKey(providerId)
            ?: return AppResult.Error(message = "No API key configured", code = ErrorCode.VALIDATION_ERROR)

        val adapter = adapterFactory.getAdapter(provider.type)
        return adapter.testConnection(provider.apiBaseUrl, apiKey)
    }

    override fun getGlobalDefaultModel(): Flow<AiModel?> =
        modelDao.getDefaultModel().map { it?.toDomain() }

    override suspend fun setGlobalDefaultModel(modelId: String, providerId: String) {
        modelDao.clearAllDefaults()
        modelDao.setDefault(modelId, providerId)
    }
}
