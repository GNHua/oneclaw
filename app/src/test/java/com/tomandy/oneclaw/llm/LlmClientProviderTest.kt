package com.tomandy.oneclaw.llm

import com.tomandy.oneclaw.data.ModelPreferences
import com.tomandy.oneclaw.security.CredentialVault
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LlmClientProviderTest {

    private lateinit var mockVault: CredentialVault
    private lateinit var mockPrefs: ModelPreferences
    private lateinit var provider: LlmClientProvider

    @Before
    fun setup() {
        mockVault = mockk()
        mockPrefs = mockk(relaxed = true)
        coEvery { mockVault.getApiKey(any()) } returns null
        every { mockPrefs.getSelectedModel() } returns null
        provider = LlmClientProvider(mockVault, mockPrefs)
    }

    @Test
    fun `loadApiKeys selects Anthropic when all providers have keys`() = runTest {
        coEvery { mockVault.listProviders() } returns listOf("OpenAI", "Google Gemini", "Anthropic")

        provider.loadApiKeys()

        assertEquals(LlmProvider.ANTHROPIC, provider.selectedProvider.value)
    }

    @Test
    fun `loadApiKeys selects Gemini when Anthropic key is missing`() = runTest {
        coEvery { mockVault.listProviders() } returns listOf("OpenAI", "Google Gemini")

        provider.loadApiKeys()

        assertEquals(LlmProvider.GEMINI, provider.selectedProvider.value)
    }

    @Test
    fun `loadApiKeys selects OpenAI when only OpenAI key exists`() = runTest {
        coEvery { mockVault.listProviders() } returns listOf("OpenAI")

        provider.loadApiKeys()

        assertEquals(LlmProvider.OPENAI, provider.selectedProvider.value)
    }

    @Test
    fun `loadApiKeys falls back to OpenAI when no providers have keys`() = runTest {
        coEvery { mockVault.listProviders() } returns emptyList()

        provider.loadApiKeys()

        assertEquals(LlmProvider.OPENAI, provider.selectedProvider.value)
    }

    @Test
    fun `loadApiKeys restores provider from saved model when key exists`() = runTest {
        coEvery { mockVault.listProviders() } returns listOf("Anthropic", "Google Gemini")
        every { mockPrefs.getSelectedModel() } returns "gemini-2.5-flash"

        provider.loadApiKeys()

        assertEquals(LlmProvider.GEMINI, provider.selectedProvider.value)
    }

    @Test
    fun `loadApiKeys ignores saved model when provider key is missing`() = runTest {
        coEvery { mockVault.listProviders() } returns listOf("OpenAI")
        every { mockPrefs.getSelectedModel() } returns "claude-sonnet-4-5"

        provider.loadApiKeys()

        assertEquals(LlmProvider.OPENAI, provider.selectedProvider.value)
    }

    @Test
    fun `getAvailableModels returns models only for providers with keys`() = runTest {
        coEvery { mockVault.listProviders() } returns listOf("OpenAI", "Anthropic")

        val models = provider.getAvailableModels()

        val providers = models.map { it.second }.toSet()
        assertTrue(providers.contains(LlmProvider.OPENAI))
        assertTrue(providers.contains(LlmProvider.ANTHROPIC))
        assertTrue(models.none { it.second == LlmProvider.GEMINI })
    }

    @Test
    fun `getProviderForModel returns correct provider for known model`() {
        assertEquals(LlmProvider.ANTHROPIC, provider.getProviderForModel("claude-sonnet-4-5"))
        assertEquals(LlmProvider.GEMINI, provider.getProviderForModel("gemini-2.5-flash"))
        assertEquals(LlmProvider.OPENAI, provider.getProviderForModel("gpt-4o"))
    }

    @Test
    fun `getProviderForModel returns null for unknown model`() {
        assertNull(provider.getProviderForModel("nonexistent-model"))
    }

    @Test
    fun `setModelAndProvider updates provider and saves model`() {
        provider.setModelAndProvider("gemini-2.5-flash")

        assertEquals(LlmProvider.GEMINI, provider.selectedProvider.value)
        verify { mockPrefs.saveSelectedModel("gemini-2.5-flash") }
    }

    @Test
    fun `setModelAndProvider does nothing for unknown model`() {
        val before = provider.selectedProvider.value

        provider.setModelAndProvider("unknown-model")

        assertEquals(before, provider.selectedProvider.value)
        verify(exactly = 0) { mockPrefs.saveSelectedModel(any()) }
    }
}
