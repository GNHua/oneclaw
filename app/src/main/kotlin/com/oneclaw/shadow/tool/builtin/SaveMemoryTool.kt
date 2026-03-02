package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.feature.memory.MemoryManager
import com.oneclaw.shadow.tool.engine.Tool

class SaveMemoryTool(
    private val memoryManager: MemoryManager
) : Tool {

    override val definition = ToolDefinition(
        name = "save_memory",
        description = """Save information to persistent long-term memory (MEMORY.md). This content is injected into the system prompt of ALL future conversations, so be highly selective.

SAVE when:
- User explicitly asks you to remember something
- You identify a STABLE user preference confirmed across 2+ conversations (not a one-time setting change)
- Important personal context: profession, domain expertise, key ongoing projects
- Recurring workflow patterns you have observed multiple times

DO NOT save:
- Transient state: model selection, temporary config, session-level settings
- One-time observations: screenshot contents, visual environment details, room decor
- Frequently changing info: "currently working on X", "today's task is Y"
- Information already present in the system prompt memory section (check before saving!)
- Inferred traits from a single interaction

Before saving, verify:
1. Will this still be relevant 30 days from now?
2. Is this already in the memory section of the system prompt? If yes, do not duplicate.
3. Is this a confirmed pattern (2+ occurrences) or a one-time event?

Write concise, factual entries. Prefer updating existing facts over adding new entries that contradict old ones.""",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "content" to ToolParameter(
                    type = "string",
                    description = "The text to save to long-term memory. Must be concise, factual, " +
                        "and self-contained. Avoid duplicating existing memory entries. Max 5,000 characters."
                ),
                "category" to ToolParameter(
                    type = "string",
                    description = "The memory section to place this entry in. " +
                        "One of: profile, preferences, interests, workflow, projects, notes. " +
                        "Defaults to notes if not specified."
                )
            ),
            required = listOf("content")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        // 1. Extract and validate content parameter
        val content = (parameters["content"] as? String)?.trim()
        if (content.isNullOrEmpty()) {
            return ToolResult.error(
                "validation_error",
                "Parameter 'content' is required and must be non-empty."
            )
        }
        if (content.length > MAX_CONTENT_LENGTH) {
            return ToolResult.error(
                "validation_error",
                "Parameter 'content' must be $MAX_CONTENT_LENGTH characters or less. " +
                    "Current length: ${content.length}."
            )
        }

        // 2. Read existing memory before saving (Phase 2)
        val existingMemory = memoryManager.readLongTermMemory()

        // 3. Basic substring deduplication check -- only for content longer than MIN_DEDUP_LENGTH (Phase 2)
        val contentNormalized = content.lowercase().trim()
        val existingNormalized = existingMemory.lowercase()
        if (contentNormalized.length > MIN_DEDUP_LENGTH && existingNormalized.contains(contentNormalized)) {
            return ToolResult.error(
                "duplicate_detected",
                "This content already exists in MEMORY.md. Use update_memory to modify existing entries."
            )
        }

        // 4. Category routing (Phase 3)
        val category = (parameters["category"] as? String)?.trim()?.lowercase()
        val sectionName = if (category != null) {
            when (category) {
                "profile" -> "User Profile"
                "preferences" -> "Preferences"
                "interests" -> "Interests"
                "workflow" -> "Workflow"
                "projects" -> "Projects"
                else -> "Notes"
            }
        } else null

        // 5. Save to long-term memory
        val result = if (sectionName != null) {
            memoryManager.saveToLongTermMemoryInSection(content, sectionName)
        } else {
            memoryManager.saveToLongTermMemory(content)
        }

        return result.fold(
            onSuccess = {
                val memoryPreview = if (existingMemory.length > 500) {
                    existingMemory.take(500) + "\n... (truncated, ${existingMemory.length} chars total)"
                } else {
                    existingMemory
                }
                ToolResult.success(
                    "Memory saved successfully.\n\n" +
                        "Current MEMORY.md content (for reference -- avoid saving duplicates):\n" +
                        memoryPreview
                )
            },
            onFailure = { e ->
                ToolResult.error("save_failed", "Failed to save memory: ${e.message}")
            }
        )
    }

    companion object {
        const val MAX_CONTENT_LENGTH = 5_000
        const val MIN_DEDUP_LENGTH = 20
    }
}
