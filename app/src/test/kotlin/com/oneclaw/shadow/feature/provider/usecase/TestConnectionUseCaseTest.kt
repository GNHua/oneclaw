package com.oneclaw.shadow.feature.provider.usecase

import com.oneclaw.shadow.core.model.ConnectionErrorType
import com.oneclaw.shadow.core.model.ConnectionTestResult
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

class TestConnectionUseCaseTest {

    private lateinit var providerRepository: ProviderRepository
    private lateinit var useCase: TestConnectionUseCase

    @BeforeEach
    fun setup() {
        providerRepository = mockk()
        useCase = TestConnectionUseCase(providerRepository)
    }

    @Test
    fun `invoke returns success when connection test succeeds`() = runTest {
        val expectedResult = ConnectionTestResult(
            success = true,
            modelCount = 5,
            errorType = null,
            errorMessage = null
        )
        coEvery { providerRepository.testConnection("provider-openai") } returns
            AppResult.Success(expectedResult)

        val result = useCase("provider-openai")

        assertTrue(result is AppResult.Success)
        val data = (result as AppResult.Success).data
        assertTrue(data.success)
        assertEquals(5, data.modelCount)
    }

    @Test
    fun `invoke returns error when no API key configured`() = runTest {
        coEvery { providerRepository.testConnection("provider-openai") } returns
            AppResult.Error(message = "No API key configured", code = ErrorCode.VALIDATION_ERROR)

        val result = useCase("provider-openai")

        assertTrue(result is AppResult.Error)
    }

    @Test
    fun `invoke returns connection result with auth failure`() = runTest {
        val expectedResult = ConnectionTestResult(
            success = false,
            modelCount = null,
            errorType = ConnectionErrorType.AUTH_FAILURE,
            errorMessage = "Authentication failed."
        )
        coEvery { providerRepository.testConnection("provider-openai") } returns
            AppResult.Success(expectedResult)

        val result = useCase("provider-openai")

        assertTrue(result is AppResult.Success)
        val data = (result as AppResult.Success).data
        assertTrue(!data.success)
        assertEquals(ConnectionErrorType.AUTH_FAILURE, data.errorType)
    }

    @Test
    fun `invoke returns connection result with network failure`() = runTest {
        val expectedResult = ConnectionTestResult(
            success = false,
            modelCount = null,
            errorType = ConnectionErrorType.NETWORK_FAILURE,
            errorMessage = "Cannot reach the server."
        )
        coEvery { providerRepository.testConnection("provider-gemini") } returns
            AppResult.Success(expectedResult)

        val result = useCase("provider-gemini")

        assertTrue(result is AppResult.Success)
        val data = (result as AppResult.Success).data
        assertEquals(ConnectionErrorType.NETWORK_FAILURE, data.errorType)
    }
}
