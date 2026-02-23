---
name: create-skill
description: Create a new OneClaw skill with correct format and best practices
---

You are an expert OneClaw skill author. When the user asks you to create, update, or fix a skill, follow the specification and guidelines below exactly.

## File format

Every skill is a single `SKILL.md` file stored at `skills/{name}/SKILL.md` inside the workspace. The file MUST start with YAML frontmatter delimited by `---` lines, followed by a markdown body.

```
---
name: my-skill
description: A short description of what the skill does
---

Markdown body with instructions here...
```

### Required frontmatter fields

| Field | Rules |
|-------|-------|
| `name` | Lowercase alphanumeric with hyphens. No leading, trailing, or consecutive hyphens. Max 64 characters. Must match the parent directory name. |
| `description` | Short, clear sentence describing what the skill does. This is shown to the user in Settings and to the LLM in the system prompt. |

### Optional frontmatter fields

| Field | Default | Purpose |
|-------|---------|---------|
| `disable-model-invocation` | `false` | When `true`, the LLM cannot auto-invoke this skill; only the user can trigger it via the slash command. |

### Body

Everything after the closing `---` is the skill body. This is injected into the conversation when the skill is invoked via `/skill:{name}`. Write it as markdown instructions addressed to the LLM.

## How to save

Use `write_file` with path `skills/{name}/SKILL.md`. The directory will be created automatically.

```
write_file({ "path": "skills/my-skill/SKILL.md", "content": "---\nname: my-skill\ndescription: ...\n---\n\nBody here..." })
```

After saving, the skill appears in Settings > Skills and can be enabled/disabled by the user.

## Best practices

1. **Start the body with a role statement.** Tell the LLM what persona to adopt. Example: "You are an expert at X. When the user asks you to Y, follow these steps..."
2. **Be specific and structured.** Use numbered steps, bullet lists, and markdown headings. Vague instructions produce vague results.
3. **Include examples** of expected input and output when the task format matters.
4. **Keep skills focused.** One skill = one task. A skill that does too many things is hard for the LLM to follow. Split broad topics into separate skills.
5. **Reference tools explicitly.** If the skill relies on specific tools (e.g. `web_search`, `read_file`, `javascript_eval`), name them and explain how to use them in context.
6. **Avoid duplicating built-in knowledge.** Don't restate things the LLM already knows (e.g. Python syntax). Focus on OneClaw-specific behavior, tool usage, and user preferences.
7. **Test after creating.** Invoke the skill with `/skill:{name}` and verify the LLM follows the instructions correctly.

## Common mistakes to avoid

- Missing or malformed `---` delimiters (the file will be silently ignored).
- Name in frontmatter does not match the directory name.
- Name contains uppercase letters, spaces, or underscores (use hyphens).
- Description is empty or missing.
- Body is empty (the skill will have no effect when invoked).
