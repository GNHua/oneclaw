package com.oneclaw.shadow.feature.provider.usecase

import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.ErrorCode

class SetDefaultModelUseCase(
    private val providerRepository: ProviderRepository
) {
    suspend operator fun invoke(modelId: String, providerId: String): AppResult<Unit> {
        val models = providerRepository.getModelsForProvider(providerId)
        val model = models.find { it.id == modelId }
            ?: return AppResult.Error(
                message = "Model not found",
                code = ErrorCode.VALIDATION_ERROR
            )

        val provider = providerRepository.getProviderById(providerId)
            ?: return AppResult.Error(
                message = "Provider not found",
                code = ErrorCode.VALIDATION_ERROR
            )

        if (!provider.isActive) {
            return AppResult.Error(
                message = "Cannot set a default model from an inactive provider.",
                code = ErrorCode.VALIDATION_ERROR
            )
        }

        providerRepository.setGlobalDefaultModel(model.id, providerId)
        return AppResult.Success(Unit)
    }
}
