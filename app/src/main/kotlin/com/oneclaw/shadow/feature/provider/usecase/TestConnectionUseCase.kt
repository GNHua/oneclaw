package com.oneclaw.shadow.feature.provider.usecase

import com.oneclaw.shadow.core.model.ConnectionTestResult
import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.core.util.AppResult

class TestConnectionUseCase(
    private val providerRepository: ProviderRepository
) {
    suspend operator fun invoke(providerId: String): AppResult<ConnectionTestResult> {
        return providerRepository.testConnection(providerId)
    }
}
