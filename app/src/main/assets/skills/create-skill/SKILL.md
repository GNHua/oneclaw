---
name: create-skill
display_name: Create Skill
description: Guide the user through creating a custom prompt skill
version: "1.0"
tools_required:
  - write_file
parameters:
  - name: idea
    type: string
    required: false
    description: Brief description of what the skill should do
---

# Create Skill

You are helping the user create a custom prompt skill for the OneClawShadow AI agent.

## What is a Skill?

A skill is a reusable prompt template that guides the AI through a specific workflow. Skills can:
- Define parameters that get substituted into the prompt using `{{parameter_name}}` syntax
- Declare which tools they need (`tools_required`)
- Be triggered via `/` commands, UI buttons, or AI self-invocation

## Your Workflow

1. **Understand Requirements**: Ask the user what the skill should do. If they provided
   an {{idea}}, start from that. Clarify:
   - What task does this skill help accomplish?
   - What inputs (parameters) does the user need to provide?
   - Which built-in tools does the skill need?
   - What step-by-step instructions should the AI follow?

2. **Design the Skill**: Based on requirements, design:
   - A clear skill name (lowercase, hyphens, e.g., `analyze-code`, `draft-email`)
   - A display name (human-readable, e.g., "Analyze Code", "Draft Email")
   - A one-line description
   - Parameters with types and descriptions
   - The full prompt content with detailed instructions

3. **Show for Review**: Present the complete SKILL.md to the user:
   - Skill metadata (name, display_name, description, version)
   - Parameters table
   - Tools required
   - Full prompt content
   - Ask: "Should I create this skill?"

4. **Create**: Only after the user confirms, use `write_file` to save the file.
   The file path must be: `skills/<skill-name>/SKILL.md`

5. **Verify**: After creation, tell the user the skill is available and can be
   triggered via `/skill-name` in the chat input.

## SKILL.md File Format

A skill file has two parts: YAML frontmatter (metadata) and Markdown body (prompt).

```
---
name: my-skill
display_name: My Skill
description: One-line description of what this skill does
version: "1.0"
tools_required:
  - tool_name_1
  - tool_name_2
parameters:
  - name: param_one
    type: string
    required: true
    description: Description of the parameter
  - name: param_two
    type: string
    required: false
    description: Description of the optional parameter
---

# My Skill

Prompt content goes here. Use {{param_one}} to reference parameters.

## Instructions
1. First step...
2. Second step...
```

## Available Built-in Tools

The following tools can be referenced in `tools_required`:

| Tool | Description |
|------|-------------|
| `read_file` | Read file contents from the device |
| `write_file` | Write content to a file on the device |
| `http_request` | Make HTTP requests (GET, POST, PUT, DELETE) |
| `get_time` | Get current date and time |
| `create_js_tool` | Create a custom JavaScript tool at runtime |
| `load_skill` | Load another skill's full instructions |

## Skill Design Guidelines

- **Name**: Lowercase letters, numbers, hyphens only. 2-50 characters.
  Must match: `^[a-z0-9][a-z0-9-]{0,48}[a-z0-9]$`
- **Description**: One concise line -- this is what appears in the skill registry
- **Parameters**: Every `{{placeholder}}` in the prompt body must have a matching
  parameter definition in the frontmatter
- **Prompt Content**: Write clear, step-by-step instructions. Be specific about:
  - What the AI should do at each step
  - How to use the required tools
  - What output format to produce
  - Edge cases to handle
- **Modularity**: One skill = one coherent workflow. Keep it focused.
- **Tool Dependencies**: Only list tools that the prompt actually instructs the AI to use

## Important Rules

- ALWAYS show the complete SKILL.md to the user and wait for confirmation before creating
- Validate the skill name format before proceeding
- Ensure all `{{parameter}}` placeholders have matching parameter definitions
- Keep the prompt content focused and actionable
- Do not create skills that duplicate existing ones
