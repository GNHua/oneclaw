---
name: device-info
display_name: "Device Info"
description: "Gather and report device information"
version: "1.0"
parameters:
  - name: detail_level
    type: string
    required: false
    description: "Level of detail: basic or full (default: basic)"
---

# Device Info

## Instructions

1. Report the following device information that you know about this session:
   - **Platform**: Android
   - **App Name**: OneClawShadow
   - **Session Time**: Current date and time (use get_current_time if available)
   - **Agent**: The agent currently active in this session

2. If `detail_level` is "full" ({{detail_level}}), also include:
   - Any additional context about the current conversation session
   - Number of messages in this conversation

3. Format the output as a clear, readable report.
4. Note any information that is unavailable.
