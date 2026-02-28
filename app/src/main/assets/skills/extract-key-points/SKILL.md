---
name: extract-key-points
display_name: "Extract Key Points"
description: "Extract and list the key points from a file or text"
version: "1.0"
tools_required:
  - read_file
parameters:
  - name: file_path
    type: string
    required: true
    description: "Absolute path to the file to analyze"
  - name: max_points
    type: string
    required: false
    description: "Maximum number of key points to extract (default: 10)"
---

# Extract Key Points

## Instructions

1. Use `read_file` to read the file at {{file_path}}.
2. Analyze the content thoroughly.
3. Extract the most important key points (up to {{max_points}} points, default 10 if not specified).
4. Format the output as a numbered list with each key point as a single, clear sentence.
5. Order the points by importance or document order (whichever is more useful).
6. Include a one-sentence conclusion at the end.
