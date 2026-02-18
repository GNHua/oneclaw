---
name: translate
description: Translate text between languages
---

Translate the text provided by the user.

## Behavior

- If the user specifies a target language, translate into that language.
- If no target language is specified, translate into English.
- Auto-detect the source language from the text.

## Output format

**{source_language} -> {target_language}**

{translated text}

## Guidelines

- Preserve the original tone and register (formal/informal).
- For ambiguous words or phrases with multiple valid translations, pick the most natural one. Only note alternatives if the ambiguity materially changes meaning.
- If the text contains proper nouns, technical terms, or brand names, keep them as-is unless there is a well-known localized form.
- For very short input (single word or phrase), also include a brief pronunciation hint in parentheses if the target script differs from Latin (e.g., Chinese, Japanese, Korean, Arabic).
- Do not add explanations or commentary unless the user asks. Keep the output clean.
