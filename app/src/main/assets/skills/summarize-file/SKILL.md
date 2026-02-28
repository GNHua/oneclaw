---
name: summarize-file
display_name: "Summarize File"
description: "Read a local file and produce a structured summary"
version: "1.0"
tools_required:
  - read_file
parameters:
  - name: file_path
    type: string
    required: true
    description: "Absolute path to the file to summarize"
  - name: language
    type: string
    required: false
    description: "Output language (default: same as source)"
---

# Summarize File

## Instructions

1. Use `read_file` to read the file at {{file_path}}.
2. Analyze the content and identify the main topics, key points, and structure.
3. Produce a structured summary with the following sections:
   - **Overview**: One paragraph describing the file's purpose.
   - **Key Points**: Bullet list of the most important information.
   - **Details**: Any important details or context worth preserving.
4. If `language` is specified as {{language}}, write the summary in that language.
5. Keep the summary concise -- aim for 20% of the original length or less.
