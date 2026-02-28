---
name: translate-file
display_name: "Translate File"
description: "Translate a file's content to a target language"
version: "1.0"
tools_required:
  - read_file
parameters:
  - name: file_path
    type: string
    required: true
    description: "Absolute path to the file to translate"
  - name: target_language
    type: string
    required: true
    description: "Target language (e.g., English, Chinese, Spanish)"
---

# Translate File

## Instructions

1. Use `read_file` to read the file at {{file_path}}.
2. Identify the source language of the content.
3. Translate the content to {{target_language}}.
4. Preserve the original structure, formatting, and meaning as closely as possible.
5. For technical terms or proper nouns, keep the original alongside the translation in parentheses when helpful.
6. Present the full translated content.
