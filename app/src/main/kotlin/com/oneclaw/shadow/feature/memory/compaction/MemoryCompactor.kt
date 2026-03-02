package com.oneclaw.shadow.feature.memory.compaction

import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.data.remote.adapter.ModelApiAdapterFactory
import com.oneclaw.shadow.data.security.ApiKeyStorage
import com.oneclaw.shadow.feature.memory.longterm.LongTermMemoryManager
import com.oneclaw.shadow.feature.memory.storage.MemoryFileStorage
import kotlinx.coroutines.flow.first

/**
 * LLM-driven memory compaction.
 * Reads MEMORY.md, backs it up, sends it to the LLM with a compaction prompt,
 * and overwrites with the compacted result.
 */
open class MemoryCompactor(
    internal val longTermMemoryManager: LongTermMemoryManager,
    internal val memoryFileStorage: MemoryFileStorage,
    private val providerRepository: ProviderRepository,
    private val apiKeyStorage: ApiKeyStorage,
    private val adapterFactory: ModelApiAdapterFactory
) {
    companion object {
        const val SIZE_THRESHOLD_CHARS = 3_000
        const val MAX_INPUT_CHARS = 10_000
        private const val MAX_TOKENS = 2_000
    }

    /**
     * Compact MEMORY.md if it exceeds the size threshold.
     * Returns true if compaction was performed, false if skipped.
     */
    suspend fun compactIfNeeded(): Boolean {
        val content = longTermMemoryManager.readMemory()
        if (content.length < SIZE_THRESHOLD_CHARS) return false
        return compact(content)
    }

    /**
     * Force compaction regardless of size.
     */
    suspend fun forceCompact(): Boolean {
        val content = longTermMemoryManager.readMemory()
        if (content.isBlank()) return false
        return compact(content)
    }

    /**
     * Call the LLM with a compaction prompt and return the result.
     * Extracted as an open method to allow overriding in tests.
     * Returns null if the call fails or if no model is configured.
     */
    internal open suspend fun callLlm(prompt: String): String? {
        return try {
            val defaultModel = providerRepository.getGlobalDefaultModel().first() ?: return null
            val provider = providerRepository.getProviderById(defaultModel.providerId) ?: return null
            if (!provider.isActive) return null
            val apiKey = apiKeyStorage.getApiKey(provider.id) ?: return null
            val adapter = adapterFactory.getAdapter(provider.type)
            when (val result = adapter.generateSimpleCompletion(
                apiBaseUrl = provider.apiBaseUrl,
                apiKey = apiKey,
                modelId = defaultModel.id,
                prompt = prompt,
                maxTokens = MAX_TOKENS
            )) {
                is AppResult.Success -> result.data
                is AppResult.Error -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun compact(content: String): Boolean {
        // 1. Backup before compaction -- this is the safety net
        memoryFileStorage.createBackup()

        val truncated = if (content.length > MAX_INPUT_CHARS) {
            content.take(MAX_INPUT_CHARS) + "\n\n[... truncated ...]"
        } else {
            content
        }

        val prompt = buildCompactionPrompt(truncated)

        // 2. Call LLM for compaction
        val response = callLlm(prompt) ?: return false

        // 3. Validate the response -- keep original if suspiciously short or blank
        if (response.isBlank() || response.length < 50) {
            return false
        }

        // 4. Overwrite with compacted content
        longTermMemoryManager.writeMemory(response)

        // 5. Prune old backups (keep most recent N)
        memoryFileStorage.pruneOldBackups()

        return true
    }

    private fun buildCompactionPrompt(content: String): String = """
You are a memory compaction assistant. Your job is to clean up and reorganize a user's long-term memory file.

## Input
The following is the current content of MEMORY.md:

```
$content
```

## Instructions
1. MERGE duplicate entries -- if the same fact appears multiple times, keep only the most recent/accurate version
2. REMOVE contradictions -- if two entries conflict, keep only the latest one
3. REMOVE transient information -- model preferences, temporary settings, one-time observations
4. PRESERVE entries that the user explicitly asked to remember
5. ORGANIZE into these standard sections:
   - ## User Profile (profession, background, personality)
   - ## Preferences (stable preferences for tools, UI, workflow)
   - ## Interests (hobbies, topics of interest)
   - ## Workflow (recurring tasks, automation preferences, tool usage patterns)
   - ## Projects (ongoing projects, tech stack details)
   - ## Notes (anything that doesn't fit above)
6. Write concise bullet points (- prefix) under each section
7. Remove empty sections entirely
8. Keep the header "# Long-term Memory" at the top

## Output
Return ONLY the compacted MEMORY.md content. No explanation, no commentary. Start with "# Long-term Memory".""".trimIndent()
}
