---
name: summarize-url
description: Summarize the content of a web page or article
---

Summarize web content fetched via URL.

## Steps

1. Use `http_get` to fetch the URL provided by the user.
2. The response will be raw HTML or text. Extract the main article content, ignoring navigation, ads, footers, and boilerplate.
3. Produce a summary with:
   - **Title** of the page/article
   - **Key points** as a bulleted list (3-7 bullets capturing the essential information)
   - **One-line takeaway** at the end

## Guidelines

- Keep the summary concise -- the whole point is to save the user from reading the full page on a small screen.
- If the content is very short (under ~200 words), just present it directly instead of summarizing.
- If the fetch fails or returns non-text content, tell the user and suggest alternatives.
- If the user provides multiple URLs, summarize each one separately.
- Preserve any important numbers, dates, or names from the source.
