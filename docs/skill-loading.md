---
layout: default
---

# Skill Loading Architecture

How OneClaw avoids dumping every skill into the system prompt.

---

## Two-Phase Approach: Enable/Disable Filter + Lazy Injection

### Phase 1: Only enabled skills enter the system prompt

Skills go through a filtering pipeline before they can appear in the prompt:

1. **Load** -- `SkillLoader` reads `SKILL.md` files from bundled assets and user directories
2. **Parse frontmatter** -- Each skill declares metadata in YAML frontmatter:
   ```yaml
   ---
   name: code-review
   description: Review code for bugs and improvements
   disable-model-invocation: false
   ---
   ```
   Recognized fields: `name` (required), `description` (required), `disable-model-invocation` (optional boolean). Unknown fields are silently ignored.
3. **Enable/disable** -- `SkillPreferences` checks per-skill toggle in SharedPreferences (user can turn skills on/off in the UI)
4. **Final list** -- Only enabled skills make it through:
   ```kotlin
   fun getEnabledSkills(): List<SkillEntry> {
       return _skills.value.filter { skill ->
           preferences.isSkillEnabled(skill.metadata.name)
       }
   }
   ```

### Phase 2: Only metadata goes into the system prompt

`SystemPromptBuilder` appends a compact XML block listing just the skill name and description -- not the full skill body:

```xml
<available_skills>
  <skill>
    <name>explain</name>
    <description>Explain a concept clearly and concisely</description>
    <location>/skills/explain/SKILL.md</location>
  </skill>
  <skill>
    <name>code-review</name>
    <description>Review code for bugs and improvements</description>
    <location>/skills/code-review/SKILL.md</location>
  </skill>
</available_skills>
```

Skills with `disable-model-invocation: true` are filtered out of this XML block entirely -- the LLM never sees them. They can only be invoked manually by the user via slash command.

The **full skill body** (the detailed instructions after the frontmatter) is only injected when the user invokes the slash command. The command format is `/skill:<name>` (e.g., `/skill:code-review`). `SlashCommandRouter` finds the matching skill and injects its full markdown body into the conversation at that point -- not before.

---

## Key Insight

**Don't inject skill instructions eagerly.** Instead:

1. Filter by enabled state
2. Only put short descriptions in the system prompt (so the LLM knows skills exist)
3. Inject the full skill body on-demand when the user types the slash command

This keeps the system prompt compact regardless of how many skills are installed.

---

## Implementation

| File | Purpose |
|------|---------|
| `SkillLoader.kt` | Loads SKILL.md from assets and user directories |
| `SkillFrontmatterParser.kt` | YAML frontmatter extraction (name, description, disable-model-invocation) |
| `SkillMetadata.kt` / `SkillEntry.kt` | Data models |
| `SkillRepository.kt` | Central registry with enable/disable filtering |
| `SkillPreferences.kt` | Per-skill enable/disable persistence |
| `SystemPromptBuilder.kt` | XML block generation for system prompt |
| `SlashCommandRouter.kt` | Command parsing and on-demand skill body resolution |

All files live in the `skill-engine` module under `com.tomandy.oneclaw.skill`.
