# PDF Tools

## Feature Information
- **Feature ID**: FEAT-033
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: RFC-033

## User Story

**As** an AI agent using OneClawShadow,
**I want** tools to read PDF files -- extracting metadata, text content, and rendering pages as images,
**so that** I can help users understand, summarize, and analyze PDF documents on their device.

### Typical Scenarios

1. A user shares a research paper PDF and asks the agent to summarize it. The agent calls `pdf_info` to check the page count, then calls `pdf_extract_text` to get the text content, and produces a summary.
2. A user has a scanned invoice PDF with no text layer. The agent calls `pdf_extract_text`, gets the "no text content" response, then calls `pdf_render_page` to render the page as an image for visual inspection.
3. A user asks the agent to extract specific pages from a long PDF report (e.g., "summarize pages 10-15"). The agent uses `pdf_extract_text` with the `pages` parameter to extract only the requested range.
4. A user wants to know the author and creation date of a document. The agent calls `pdf_info` to retrieve the document metadata.

## Feature Description

### Overview

FEAT-033 adds three PDF-related tools to OneClawShadow as Kotlin built-in tools, enabling AI agents to work with PDF files stored on the device. The tools are ported from the OneClaw 1.0 `lib-pdf` plugin and adapted to OneClawShadow's tool architecture.

The three tools are:
- **`pdf_info`** -- Retrieve PDF metadata (page count, file size, title, author, etc.)
- **`pdf_extract_text`** -- Extract text content from PDF files with optional page range selection
- **`pdf_render_page`** -- Render a PDF page to a PNG image for visual inspection

### Architecture Overview

```
AI Model
    | tool call: pdf_info(path="...") / pdf_extract_text(...) / pdf_render_page(...)
    v
ToolExecutionEngine  (unchanged)
    |
    v
ToolRegistry
    |
    v
PdfInfoTool / PdfExtractTextTool / PdfRenderPageTool  [NEW - Kotlin built-in tools]
    |
    +-- PDFBox Android (text extraction, metadata reading)
    |
    +-- Android PdfRenderer (page rendering to bitmap)
    |
    +-- PdfToolUtils [NEW - shared path resolution, page range parsing]
```

### Tool Definitions

#### pdf_info

| Field | Value |
|-------|-------|
| Name | `pdf_info` |
| Description | Get metadata and info about a PDF file |
| Parameters | `path` (string, required): Path to the PDF file |
| Required Permissions | `READ_EXTERNAL_STORAGE` |
| Timeout | 15 seconds |
| Returns | Text with page count, file size, title, author, and other document properties |

#### pdf_extract_text

| Field | Value |
|-------|-------|
| Name | `pdf_extract_text` |
| Description | Extract text content from a PDF file |
| Parameters | `path` (string, required): Path to the PDF file |
| | `pages` (string, optional): Page range (e.g., "1-5", "3", "1,3,5-7"). Omit for all pages |
| | `max_chars` (integer, optional): Maximum characters to return. Default: 50000 |
| Required Permissions | `READ_EXTERNAL_STORAGE` |
| Timeout | 30 seconds |
| Returns | Extracted text with header showing filename, page range, and total page count |

#### pdf_render_page

| Field | Value |
|-------|-------|
| Name | `pdf_render_page` |
| Description | Render a PDF page to a PNG image |
| Parameters | `path` (string, required): Path to the PDF file |
| | `page` (integer, required): Page number to render (1-based) |
| | `dpi` (integer, optional): Render resolution in DPI (default 150, range 72-300) |
| Required Permissions | `READ_EXTERNAL_STORAGE` |
| Timeout | 30 seconds |
| Returns | Text with output file path, resolution, and file size |

### User Interaction Flow

```
1. User: "Help me summarize this PDF"
   (User has shared a PDF via file attachments or the file is in accessible storage)
2. AI calls pdf_info(path="/sdcard/Documents/report.pdf")
3. pdf_info returns: "Pages: 42, Title: Annual Report 2025, ..."
4. AI calls pdf_extract_text(path="/sdcard/Documents/report.pdf", max_chars=80000)
5. pdf_extract_text returns: extracted text content
6. AI produces a summary based on the extracted text
```

```
1. User: "What's on page 3 of this scanned PDF?"
2. AI calls pdf_extract_text(path="...", pages="3")
3. Returns: "No text content found. This may be a scanned document."
4. AI calls pdf_render_page(path="...", page=3, dpi=200)
5. Returns: "Page 3 rendered and saved to: pdf-renders/document-page3.png"
6. AI uses the rendered image for visual analysis
```

## Acceptance Criteria

Must pass (all required):

- [ ] TEST-033-01: `pdf_info` is registered as a Kotlin built-in tool in `ToolRegistry`
- [ ] TEST-033-02: `pdf_extract_text` is registered as a Kotlin built-in tool in `ToolRegistry`
- [ ] TEST-033-03: `pdf_render_page` is registered as a Kotlin built-in tool in `ToolRegistry`
- [ ] TEST-033-04: `pdf_info` returns page count, file size, and available metadata fields
- [ ] TEST-033-05: `pdf_extract_text` returns text content from a text-based PDF
- [ ] TEST-033-06: `pdf_extract_text` supports page range selection ("1-5", "3", "1,3,5-7")
- [ ] TEST-033-07: `pdf_extract_text` returns a helpful message for scanned PDFs with no text layer
- [ ] TEST-033-08: `pdf_extract_text` truncates output at `max_chars` with a truncation notice
- [ ] TEST-033-09: `pdf_render_page` renders the specified page as a PNG image
- [ ] TEST-033-10: `pdf_render_page` saves the output to the app's internal `pdf-renders/` directory
- [ ] TEST-033-11: `pdf_render_page` respects the `dpi` parameter (clamped to 72-300)
- [ ] TEST-033-12: All three tools return appropriate errors for missing files, invalid parameters, and out-of-range pages
- [ ] TEST-033-13: All Layer 1A tests pass

Optional (nice to have):

- [ ] `pdf_extract_text` supports extracting text from password-protected PDFs (with a `password` parameter)
- [ ] `pdf_render_page` supports rendering multiple pages at once

## UI/UX Requirements

This feature has no new UI. The tools are registered in the tool system and available to AI agents:
- Tool names appear in the tool management screen (FEAT-017)
- Tool call results are displayed in the chat view (FEAT-001)

## Feature Boundary

### Included

- Three Kotlin built-in tools: `PdfInfoTool`, `PdfExtractTextTool`, `PdfRenderPageTool`
- Shared utility class: `PdfToolUtils` (path resolution, page range parsing)
- PDFBox Android dependency addition to `build.gradle.kts`
- PDFBox initialization in `OneclawApplication` or Koin module
- Update to `ToolModule` to register the three tools
- PNG output directory management for rendered pages

### Not Included (V1)

- PDF creation or editing
- PDF annotation
- OCR for scanned PDFs (rendered images can be processed by vision-capable models)
- PDF form filling
- PDF signing or encryption
- Password-protected PDF support
- PDF-to-Markdown conversion (beyond raw text extraction)

## Business Rules

1. All three tools require a valid file path to an existing PDF file
2. `pdf_extract_text` defaults to all pages if `pages` parameter is omitted
3. `pdf_extract_text` defaults to 50,000 character limit if `max_chars` is not specified
4. `pdf_render_page` uses 1-based page numbering (page 1 is the first page)
5. `pdf_render_page` DPI is clamped to the range 72-300 regardless of input
6. Rendered PNG files are saved to the app's internal storage (`filesDir/pdf-renders/`)
7. Rendered PNG filenames follow the pattern `{basename}-page{N}.png`

## Non-Functional Requirements

### Performance

- `pdf_info`: < 500ms for typical PDFs (< 50MB)
- `pdf_extract_text`: < 2s for typical PDFs (< 100 pages)
- `pdf_render_page`: < 3s per page at 150 DPI
- Memory: PDDocument objects are closed immediately after use to prevent leaks

### Security

- File path validation prevents access to restricted system directories
- PDF rendering output stays within the app's internal storage
- No network access -- all operations are local file operations
- PDFBox parser is safe against malicious PDFs (no script execution)

### Compatibility

- Requires Android API 21+ (PdfRenderer requires API 21)
- PDFBox Android is compatible with all supported Android versions
- Works with both text-based and scanned PDFs (graceful fallback for scanned)

## Dependencies

### Depends On

- **FEAT-004 (Tool System)**: Tool interface, registry, execution engine
- **FEAT-025 (File Browsing)**: File system access patterns
- **FEAT-026 (File Attachments)**: Users can share PDF files to the app

### Depended On By

- No other features currently depend on FEAT-033

### External Dependencies

- **PDFBox Android** (`com.tom-roush:pdfbox-android:2.0.27.0`): PDF text extraction and metadata reading. Apache 2.0 license.
- **Android PdfRenderer**: Built into Android framework (API 21+). Used for page-to-bitmap rendering.

## Error Handling

### Error Scenarios

1. **File not found**
   - Cause: Path points to a nonexistent file
   - Handling: Return `ToolResult.error("file_not_found", "File not found: <path>")`

2. **Not a valid PDF**
   - Cause: File exists but is not a valid PDF document
   - Handling: Return `ToolResult.error("invalid_pdf", "Failed to read PDF: <message>")`

3. **Page out of range**
   - Cause: Requested page number exceeds document page count
   - Handling: Return `ToolResult.error("invalid_page", "Page N out of range (document has M pages)")`

4. **Invalid page range**
   - Cause: Malformed page range string (e.g., "abc", "5-2")
   - Handling: Return `ToolResult.error("invalid_page_range", "Invalid page range: <spec>")`

5. **Permission denied**
   - Cause: App lacks READ_EXTERNAL_STORAGE permission
   - Handling: ToolExecutionEngine requests permission before execution

6. **Out of memory**
   - Cause: Very large PDF or high DPI rendering
   - Handling: Caught by exception handler; returns error with suggestion to use lower DPI

## Test Points

### Functional Tests

- Verify `pdf_info` returns correct page count for a known PDF
- Verify `pdf_info` returns available metadata fields (title, author, etc.)
- Verify `pdf_extract_text` extracts correct text from a text-based PDF
- Verify `pdf_extract_text` with `pages="1-3"` extracts only pages 1-3
- Verify `pdf_extract_text` with `pages="2"` extracts only page 2
- Verify `pdf_extract_text` with `pages="1,3,5-7"` extracts the specified pages
- Verify `pdf_extract_text` truncates at `max_chars` with truncation notice
- Verify `pdf_extract_text` returns scanned-document message for image-only PDFs
- Verify `pdf_render_page` creates a PNG file at the expected path
- Verify `pdf_render_page` respects DPI parameter
- Verify `pdf_render_page` clamps DPI to 72-300 range

### Edge Cases

- PDF with 0 pages (corrupted)
- PDF with 1000+ pages
- PDF with no metadata fields
- PDF larger than 100MB
- PDF with complex Unicode text (CJK, Arabic, etc.)
- Page range "1-1" (single page range)
- Page range with spaces "1 - 5"
- `max_chars` set to 0
- DPI set to 0 or negative
- DPI set to 1000 (should clamp to 300)
- File path with spaces and special characters

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
