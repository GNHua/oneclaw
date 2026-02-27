package com.oneclaw.shadow.feature.provider.usecase

import com.oneclaw.shadow.core.model.AiModel
import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.core.util.AppResult

class FetchModelsUseCase(
    private val providerRepository: ProviderRepository
) {
    /**
     * Fetch models from the provider API. If the fetch fails and the provider has
     * existing models (preset or previously fetched), those are returned instead.
     * If there are no models at all, the error is propagated.
     */
    suspend operator fun invoke(providerId: String): AppResult<List<AiModel>> {
        val fetchResult = providerRepository.fetchModelsFromApi(providerId)
        val currentModels = providerRepository.getModelsForProvider(providerId)

        return when (fetchResult) {
            is AppResult.Success -> AppResult.Success(currentModels)
            is AppResult.Error -> {
                if (currentModels.isNotEmpty()) {
                    AppResult.Success(currentModels)
                } else {
                    fetchResult
                }
            }
        }
    }
}
