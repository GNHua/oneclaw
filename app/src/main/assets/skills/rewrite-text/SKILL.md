---
name: rewrite-text
display_name: "Rewrite Text"
description: "Rewrite text in a specified style or tone"
version: "1.0"
parameters:
  - name: text
    type: string
    required: true
    description: "The text to rewrite"
  - name: style
    type: string
    required: true
    description: "Target style or tone (e.g., formal, casual, persuasive, concise, detailed)"
---

# Rewrite Text

## Instructions

1. Take the following text: {{text}}
2. Rewrite it in a {{style}} style/tone.
3. Preserve the core meaning and key information.
4. Adjust vocabulary, sentence structure, and tone to match the requested style.
5. Present the rewritten version without additional commentary unless asked.
