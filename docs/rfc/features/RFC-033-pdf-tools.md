# RFC-033: PDF Tools

## Document Information
- **RFC ID**: RFC-033
- **Related PRD**: [FEAT-033 (PDF Tools)](../../prd/features/FEAT-033-pdf-tools.md)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Related RFC**: [RFC-004 (Tool System)](RFC-004-tool-system.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

AI agents frequently need to work with PDF documents -- summarizing reports, extracting data from invoices, reading research papers, or analyzing scanned forms. Currently, OneClaw has no built-in capability to read PDF files. Users can attach files (FEAT-026) and browse the file system (FEAT-025), but agents cannot extract content from PDFs.

OneClaw 1.0 has a mature PDF tools implementation in `lib-pdf` that provides three tools: `pdf_info`, `pdf_extract_text`, and `pdf_render_page`. This RFC ports that functionality to OneClaw's tool architecture as Kotlin built-in tools, adapting the code to use OneClaw's `Tool` interface, `ToolResult`, and `ToolDefinition` data types.

### Goals

1. Implement three Kotlin built-in tools: `PdfInfoTool`, `PdfExtractTextTool`, `PdfRenderPageTool`
2. Create shared utility `PdfToolUtils` for path resolution and page range parsing
3. Add PDFBox Android dependency to the project
4. Initialize PDFBox in the application startup
5. Register all three tools in `ToolModule`
6. Add unit tests for all tools and utilities

### Non-Goals

- PDF creation, editing, or annotation
- OCR for scanned PDFs (vision-capable models can analyze rendered images)
- Password-protected PDF support (deferred to future iteration)
- PDF form interaction
- PDF-to-Markdown conversion (beyond raw text extraction)

## Technical Design

### Architecture Overview

```
┌──────────────────────────────────────────────────────────────┐
│                     Chat Layer (RFC-001)                      │
│  SendMessageUseCase                                          │
│       │                                                      │
│       │  tool call: pdf_info / pdf_extract_text /            │
│       │             pdf_render_page                           │
│       v                                                      │
├──────────────────────────────────────────────────────────────┤
│                   Tool Execution Engine (RFC-004)             │
│  executeTool(name, params, availableToolIds)                 │
│       │                                                      │
│       v                                                      │
│  ┌────────────────────────────────────────────────────────┐  │
│  │                    ToolRegistry                         │  │
│  │                                                        │  │
│  │  ┌─────────────┐  ┌──────────────────┐  ┌───────────┐ │  │
│  │  │  pdf_info    │  │ pdf_extract_text │  │pdf_render │ │  │
│  │  │(PdfInfoTool) │  │(PdfExtractText  │  │  _page    │ │  │
│  │  │             │  │        Tool)     │  │(PdfRender │ │  │
│  │  │             │  │                  │  │ PageTool) │ │  │
│  │  └──────┬──────┘  └────────┬─────────┘  └─────┬─────┘ │  │
│  │         │                  │                   │       │  │
│  │         v                  v                   v       │  │
│  │  ┌─────────────────────────────────────────────────┐   │  │
│  │  │                  PdfToolUtils                    │   │  │
│  │  │  - initPdfBox(context)                          │   │  │
│  │  │  - parsePageRange(spec, totalPages)             │   │  │
│  │  └─────────────────────────────────────────────────┘   │  │
│  │         │                  │                   │       │  │
│  │         v                  v                   v       │  │
│  │  ┌──────────────┐  ┌──────────────┐  ┌─────────────┐  │  │
│  │  │ PDFBox       │  │ PDFBox       │  │ Android     │  │  │
│  │  │ PDDocument   │  │ PDFText      │  │ PdfRenderer │  │  │
│  │  │ .docInfo     │  │ Stripper     │  │ + Bitmap    │  │  │
│  │  └──────────────┘  └──────────────┘  └─────────────┘  │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

### Core Components

**New:**
1. `PdfInfoTool` -- Kotlin built-in tool that reads PDF metadata
2. `PdfExtractTextTool` -- Kotlin built-in tool that extracts text from PDFs
3. `PdfRenderPageTool` -- Kotlin built-in tool that renders PDF pages as PNG images
4. `PdfToolUtils` -- Shared utility for PDFBox initialization and page range parsing

**Modified:**
5. `ToolModule` -- Register the three PDF tools
6. `build.gradle.kts` -- Add PDFBox Android dependency

## Detailed Design

### Directory Structure (New & Changed Files)

```
app/src/main/
├── kotlin/com/oneclaw/shadow/
│   ├── tool/
│   │   ├── builtin/
│   │   │   ├── PdfInfoTool.kt              # NEW
│   │   │   ├── PdfExtractTextTool.kt       # NEW
│   │   │   ├── PdfRenderPageTool.kt        # NEW
│   │   │   ├── WebfetchTool.kt             # unchanged
│   │   │   ├── BrowserTool.kt              # unchanged
│   │   │   ├── LoadSkillTool.kt            # unchanged
│   │   │   ├── CreateScheduledTaskTool.kt  # unchanged
│   │   │   └── CreateAgentTool.kt          # unchanged
│   │   └── util/
│   │       ├── PdfToolUtils.kt             # NEW
│   │       └── HtmlToMarkdownConverter.kt  # unchanged
│   └── di/
│       └── ToolModule.kt                   # MODIFIED

app/src/test/kotlin/com/oneclaw/shadow/
    └── tool/
        ├── builtin/
        │   ├── PdfInfoToolTest.kt           # NEW
        │   ├── PdfExtractTextToolTest.kt    # NEW
        │   └── PdfRenderPageToolTest.kt     # NEW
        └── util/
            └── PdfToolUtilsTest.kt          # NEW
```

### PdfToolUtils

```kotlin
/**
 * Located in: tool/util/PdfToolUtils.kt
 *
 * Shared utilities for PDF tools: PDFBox initialization
 * and page range parsing.
 */
object PdfToolUtils {

    private const val TAG = "PdfToolUtils"
    private var initialized = false

    /**
     * Initialize PDFBox resource loader. Must be called once
     * before any PDFBox operations. Safe to call multiple times.
     */
    fun initPdfBox(context: Context) {
        if (!initialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
            Log.i(TAG, "PDFBox initialized")
        }
    }

    /**
     * Parse a page range specification string.
     *
     * Supported formats:
     * - Single page: "3"
     * - Range: "1-5"
     * - Comma-separated: "1,3,5-7"
     *
     * @param spec Page range specification string
     * @param totalPages Total number of pages in the document
     * @return Pair of (startPage, endPage) 1-based inclusive, or null if invalid
     */
    fun parsePageRange(spec: String, totalPages: Int): Pair<Int, Int>? {
        val trimmed = spec.trim()

        // Comma-separated: find overall min and max
        if (trimmed.contains(",")) {
            val parts = trimmed.split(",").map { it.trim() }
            var min = Int.MAX_VALUE
            var max = Int.MIN_VALUE
            for (part in parts) {
                val range = parsePageRange(part, totalPages) ?: return null
                min = minOf(min, range.first)
                max = maxOf(max, range.second)
            }
            return Pair(min, max)
        }

        // Range: "start-end"
        if (trimmed.contains("-")) {
            val parts = trimmed.split("-", limit = 2)
            val start = parts[0].trim().toIntOrNull() ?: return null
            val end = parts[1].trim().toIntOrNull() ?: return null
            if (start < 1 || end > totalPages || start > end) return null
            return Pair(start, end)
        }

        // Single page
        val page = trimmed.toIntOrNull() ?: return null
        if (page < 1 || page > totalPages) return null
        return Pair(page, page)
    }
}
```

### PdfInfoTool

```kotlin
/**
 * Located in: tool/builtin/PdfInfoTool.kt
 *
 * Reads PDF metadata: page count, file size, title, author,
 * subject, creator, producer, and creation date.
 */
class PdfInfoTool(
    private val context: Context
) : Tool {

    companion object {
        private const val TAG = "PdfInfoTool"
    }

    override val definition = ToolDefinition(
        name = "pdf_info",
        description = "Get metadata and info about a PDF file. " +
            "Returns page count, file size, title, author, and other document properties.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "path" to ToolParameter(
                    type = "string",
                    description = "Path to the PDF file"
                )
            ),
            required = listOf("path")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 15
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val path = parameters["path"]?.toString()
            ?: return ToolResult.error("validation_error", "Parameter 'path' is required")

        val file = File(path)
        if (!file.exists()) {
            return ToolResult.error("file_not_found", "File not found: $path")
        }
        if (!file.canRead()) {
            return ToolResult.error("permission_denied", "Cannot read file: $path")
        }

        PdfToolUtils.initPdfBox(context)

        return try {
            val doc = PDDocument.load(file)
            val result = try {
                val info = doc.documentInformation
                val lines = mutableListOf<String>()
                lines.add("File: ${file.name}")
                lines.add("Path: $path")
                lines.add("Pages: ${doc.numberOfPages}")
                lines.add("File size: ${file.length()} bytes")

                info.title?.takeIf { it.isNotBlank() }?.let {
                    lines.add("Title: $it")
                }
                info.author?.takeIf { it.isNotBlank() }?.let {
                    lines.add("Author: $it")
                }
                info.subject?.takeIf { it.isNotBlank() }?.let {
                    lines.add("Subject: $it")
                }
                info.creator?.takeIf { it.isNotBlank() }?.let {
                    lines.add("Creator: $it")
                }
                info.producer?.takeIf { it.isNotBlank() }?.let {
                    lines.add("Producer: $it")
                }
                info.creationDate?.time?.let {
                    lines.add("Created: $it")
                }

                ToolResult.success(lines.joinToString("\n"))
            } finally {
                doc.close()
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read PDF info: $path", e)
            ToolResult.error("pdf_error", "Failed to read PDF info: ${e.message}")
        }
    }
}
```

### PdfExtractTextTool

```kotlin
/**
 * Located in: tool/builtin/PdfExtractTextTool.kt
 *
 * Extracts text content from PDF files using PDFBox's PDFTextStripper.
 * Supports page range selection and output truncation.
 */
class PdfExtractTextTool(
    private val context: Context
) : Tool {

    companion object {
        private const val TAG = "PdfExtractTextTool"
        private const val DEFAULT_MAX_CHARS = 50_000
    }

    override val definition = ToolDefinition(
        name = "pdf_extract_text",
        description = "Extract text content from a PDF file. " +
            "Supports page range selection. For scanned PDFs with no text layer, " +
            "use pdf_render_page to get page images instead.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "path" to ToolParameter(
                    type = "string",
                    description = "Path to the PDF file"
                ),
                "pages" to ToolParameter(
                    type = "string",
                    description = "Page range to extract (e.g. \"1-5\", \"3\", \"1,3,5-7\"). " +
                        "Omit to extract all pages."
                ),
                "max_chars" to ToolParameter(
                    type = "integer",
                    description = "Maximum characters to return (default 50000)"
                )
            ),
            required = listOf("path")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 30
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val path = parameters["path"]?.toString()
            ?: return ToolResult.error("validation_error", "Parameter 'path' is required")
        val maxChars = (parameters["max_chars"] as? Number)?.toInt()
            ?: DEFAULT_MAX_CHARS
        val pagesArg = parameters["pages"]?.toString()

        val file = File(path)
        if (!file.exists()) {
            return ToolResult.error("file_not_found", "File not found: $path")
        }
        if (!file.canRead()) {
            return ToolResult.error("permission_denied", "Cannot read file: $path")
        }

        PdfToolUtils.initPdfBox(context)

        return try {
            val doc = PDDocument.load(file)
            val result = try {
                val stripper = PDFTextStripper()
                val totalPages = doc.numberOfPages

                if (pagesArg != null) {
                    val range = PdfToolUtils.parsePageRange(pagesArg, totalPages)
                        ?: return ToolResult.error(
                            "invalid_page_range",
                            "Invalid page range: $pagesArg (document has $totalPages pages)"
                        )
                    stripper.startPage = range.first
                    stripper.endPage = range.second
                }

                val text = stripper.getText(doc)

                if (text.isBlank()) {
                    ToolResult.success(
                        "No text content found in PDF. This may be a scanned document. " +
                            "Use pdf_render_page to render pages as images for visual inspection."
                    )
                } else {
                    val truncated = if (text.length > maxChars) {
                        text.take(maxChars) +
                            "\n\n[Truncated at $maxChars characters. " +
                            "Total text length: ${text.length}. " +
                            "Use 'pages' parameter to extract specific pages.]"
                    } else {
                        text
                    }

                    val header = "Extracted text from ${file.name}" +
                        (if (pagesArg != null) " (pages: $pagesArg)" else "") +
                        " [$totalPages total pages]:\n\n"

                    ToolResult.success(header + truncated)
                }
            } finally {
                doc.close()
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract PDF text: $path", e)
            ToolResult.error("pdf_error", "Failed to extract PDF text: ${e.message}")
        }
    }
}
```

### PdfRenderPageTool

```kotlin
/**
 * Located in: tool/builtin/PdfRenderPageTool.kt
 *
 * Renders a PDF page to a PNG image using Android's PdfRenderer.
 * Saves the output to the app's internal pdf-renders/ directory.
 */
class PdfRenderPageTool(
    private val context: Context
) : Tool {

    companion object {
        private const val TAG = "PdfRenderPageTool"
        private const val DEFAULT_DPI = 150
        private const val MIN_DPI = 72
        private const val MAX_DPI = 300
    }

    override val definition = ToolDefinition(
        name = "pdf_render_page",
        description = "Render a PDF page to a PNG image. " +
            "Useful for scanned PDFs or pages with complex layouts, charts, or images. " +
            "The rendered image is saved to pdf-renders/ in the app's storage.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "path" to ToolParameter(
                    type = "string",
                    description = "Path to the PDF file"
                ),
                "page" to ToolParameter(
                    type = "integer",
                    description = "Page number to render (1-based)"
                ),
                "dpi" to ToolParameter(
                    type = "integer",
                    description = "Render resolution in DPI (default 150, min 72, max 300)"
                )
            ),
            required = listOf("path", "page")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 30
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val path = parameters["path"]?.toString()
            ?: return ToolResult.error("validation_error", "Parameter 'path' is required")
        val pageNum = (parameters["page"] as? Number)?.toInt()
            ?: return ToolResult.error("validation_error", "Parameter 'page' is required")
        val dpi = ((parameters["dpi"] as? Number)?.toInt() ?: DEFAULT_DPI)
            .coerceIn(MIN_DPI, MAX_DPI)

        val file = File(path)
        if (!file.exists()) {
            return ToolResult.error("file_not_found", "File not found: $path")
        }
        if (!file.canRead()) {
            return ToolResult.error("permission_denied", "Cannot read file: $path")
        }

        return try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)

            val pageIndex = pageNum - 1
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
                renderer.close()
                fd.close()
                return ToolResult.error(
                    "invalid_page",
                    "Page $pageNum out of range (document has ${renderer.pageCount} pages)"
                )
            }

            val page = renderer.openPage(pageIndex)
            val scale = dpi / 72f
            val width = (page.width * scale).toInt()
            val height = (page.height * scale).toInt()

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)

            page.render(
                bitmap,
                null,
                null,
                PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
            )
            page.close()
            renderer.close()
            fd.close()

            // Save PNG to app's internal storage
            val outputDir = File(context.filesDir, "pdf-renders").also { it.mkdirs() }
            val baseName = file.nameWithoutExtension
            val outputFile = File(outputDir, "${baseName}-page${pageNum}.png")
            FileOutputStream(outputFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()

            ToolResult.success(
                "Page $pageNum rendered and saved to: ${outputFile.absolutePath}\n" +
                    "Resolution: ${width}x${height} (${dpi} DPI)\n" +
                    "File size: ${outputFile.length()} bytes"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render PDF page: $path page $pageNum", e)
            ToolResult.error("pdf_error", "Failed to render PDF page: ${e.message}")
        }
    }
}
```

### PDFBox Android Dependency

Add to `app/build.gradle.kts`:

```kotlin
dependencies {
    // ... existing dependencies ...

    // PDF tools: PDFBox for text extraction and metadata
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
```

PDFBox Android:
- Size: ~2.5MB (includes fonts and resources)
- License: Apache 2.0
- Transitive dependencies: none significant
- Compatible with Android API 21+
- Used by OneClaw 1.0 (`lib-pdf` module)

### ToolModule Changes

```kotlin
// In ToolModule.kt, add imports:
import com.oneclaw.shadow.tool.builtin.PdfInfoTool
import com.oneclaw.shadow.tool.builtin.PdfExtractTextTool
import com.oneclaw.shadow.tool.builtin.PdfRenderPageTool
import com.oneclaw.shadow.tool.util.PdfToolUtils

val toolModule = module {
    // ... existing declarations ...

    // RFC-033: PDF tools
    single {
        PdfToolUtils.initPdfBox(androidContext())
        PdfInfoTool(androidContext())
    }
    single {
        PdfExtractTextTool(androidContext())
    }
    single {
        PdfRenderPageTool(androidContext())
    }

    single {
        ToolRegistry().apply {
            // ... existing tool registrations ...

            // RFC-033: PDF tools
            try {
                register(get<PdfInfoTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register pdf_info: ${e.message}")
            }
            try {
                register(get<PdfExtractTextTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register pdf_extract_text: ${e.message}")
            }
            try {
                register(get<PdfRenderPageTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register pdf_render_page: ${e.message}")
            }

            // ... JS tool loading (unchanged) ...
        }
    }

    // ... rest of module unchanged ...
}
```

## Implementation Plan

### Phase 1: Dependencies and Utilities

1. [ ] Add `com.tom-roush:pdfbox-android:2.0.27.0` to `app/build.gradle.kts`
2. [ ] Create `PdfToolUtils.kt` in `tool/util/`
3. [ ] Create `PdfToolUtilsTest.kt` with tests for `parsePageRange()`
4. [ ] Verify build compiles successfully

### Phase 2: PdfInfoTool

1. [ ] Create `PdfInfoTool.kt` in `tool/builtin/`
2. [ ] Create `PdfInfoToolTest.kt` with unit tests
3. [ ] Register in `ToolModule.kt`
4. [ ] Verify `./gradlew test` passes

### Phase 3: PdfExtractTextTool

1. [ ] Create `PdfExtractTextTool.kt` in `tool/builtin/`
2. [ ] Create `PdfExtractTextToolTest.kt` with unit tests
3. [ ] Register in `ToolModule.kt`
4. [ ] Verify `./gradlew test` passes

### Phase 4: PdfRenderPageTool

1. [ ] Create `PdfRenderPageTool.kt` in `tool/builtin/`
2. [ ] Create `PdfRenderPageToolTest.kt` with unit tests
3. [ ] Register in `ToolModule.kt`
4. [ ] Verify `./gradlew test` passes

### Phase 5: Integration Testing

1. [ ] Run full Layer 1A test suite (`./gradlew test`)
2. [ ] Run Layer 1B tests if emulator available
3. [ ] Manual testing with real PDF files on device
4. [ ] Write test report

## Data Model

No data model or database changes. The tools operate on files and return string results through the existing `ToolResult` type.

## API Design

### Tool Interfaces

```
Tool: pdf_info
Parameters:
  - path: string (required) -- Path to the PDF file
Returns on success:
  Multi-line text with file info, page count, and metadata fields
Returns on error:
  ToolResult.error with error type and message

Tool: pdf_extract_text
Parameters:
  - path: string (required) -- Path to the PDF file
  - pages: string (optional) -- Page range specification
  - max_chars: integer (optional, default: 50000) -- Output character limit
Returns on success:
  Header line + extracted text content
Returns on error:
  ToolResult.error with error type and message

Tool: pdf_render_page
Parameters:
  - path: string (required) -- Path to the PDF file
  - page: integer (required) -- Page number (1-based)
  - dpi: integer (optional, default: 150) -- Render resolution (72-300)
Returns on success:
  Text with output file path, resolution, and file size
Returns on error:
  ToolResult.error with error type and message
```

## Error Handling

| Error Type | Cause | Response |
|------------|-------|----------|
| `validation_error` | Missing or invalid required parameter | `ToolResult.error("validation_error", "Parameter 'X' is required")` |
| `file_not_found` | File does not exist at given path | `ToolResult.error("file_not_found", "File not found: <path>")` |
| `permission_denied` | Cannot read the file | `ToolResult.error("permission_denied", "Cannot read file: <path>")` |
| `invalid_page` | Page number out of document range | `ToolResult.error("invalid_page", "Page N out of range (document has M pages)")` |
| `invalid_page_range` | Malformed page range string | `ToolResult.error("invalid_page_range", "Invalid page range: <spec>")` |
| `pdf_error` | PDFBox or PdfRenderer exception | `ToolResult.error("pdf_error", "Failed to ...: <exception message>")` |

All errors follow the existing `ToolResult.error(errorType, errorMessage)` pattern used by other built-in tools.

## Security Considerations

1. **File access**: Tools accept file paths within app-private storage. No external storage permissions are required -- file access is confined to app-private storage (context.filesDir) via FsBridge allowlist validation.

2. **Resource management**: PDDocument, PdfRenderer, ParcelFileDescriptor, and Bitmap objects are properly closed/recycled in try-finally blocks to prevent resource leaks.

3. **Memory safety**: PDFBox may consume significant memory for large PDFs. The tools do not load the entire document into memory at once -- PDFTextStripper processes pages sequentially. PdfRenderer renders one page at a time with the bitmap recycled after saving.

4. **Output isolation**: Rendered PNG files are saved to the app's internal storage (`context.filesDir/pdf-renders/`), not to shared external storage.

5. **No network access**: All operations are local file operations. No data is sent externally.

## Performance

| Operation | Expected Time | Notes |
|-----------|--------------|-------|
| `pdf_info` (typical PDF) | < 500ms | Opens file, reads metadata, closes |
| `pdf_extract_text` (10 pages) | < 1s | PDFTextStripper sequential processing |
| `pdf_extract_text` (100 pages) | < 5s | Linear scaling with page count |
| `pdf_render_page` (150 DPI) | < 2s | Render + PNG compression |
| `pdf_render_page` (300 DPI) | < 5s | 4x pixels vs 150 DPI |

Memory usage:
- PDDocument: ~2-10MB for typical PDFs (closed after use)
- Bitmap at 150 DPI (A4 page): ~3.5MB (recycled after saving)
- Bitmap at 300 DPI (A4 page): ~14MB (recycled after saving)

## Testing Strategy

### Unit Tests

**PdfToolUtilsTest.kt:**
- `testParsePageRange_singlePage` -- "3" returns (3, 3)
- `testParsePageRange_range` -- "1-5" returns (1, 5)
- `testParsePageRange_commaSeparated` -- "1,3,5-7" returns (1, 7)
- `testParsePageRange_rangeWithSpaces` -- "1 - 5" returns (1, 5)
- `testParsePageRange_invalidRange` -- "5-2" returns null
- `testParsePageRange_outOfBounds` -- "0" and "11" for 10-page doc return null
- `testParsePageRange_nonNumeric` -- "abc" returns null
- `testParsePageRange_singlePageRange` -- "1-1" returns (1, 1)

**PdfInfoToolTest.kt:**
- `testDefinition` -- Tool definition has correct name, parameters, permissions
- `testExecute_missingPath` -- Returns validation error
- `testExecute_fileNotFound` -- Returns file_not_found error
- `testExecute_validPdf` -- Returns page count, file size, metadata (using test PDF resource)

**PdfExtractTextToolTest.kt:**
- `testDefinition` -- Tool definition has correct name, parameters, permissions
- `testExecute_missingPath` -- Returns validation error
- `testExecute_fileNotFound` -- Returns file_not_found error
- `testExecute_extractAllPages` -- Returns full text (using test PDF resource)
- `testExecute_extractPageRange` -- Returns text from specified pages
- `testExecute_invalidPageRange` -- Returns invalid_page_range error
- `testExecute_truncation` -- Returns truncated text with notice when exceeding max_chars
- `testExecute_defaultMaxChars` -- Default max_chars is 50000

**PdfRenderPageToolTest.kt:**
- `testDefinition` -- Tool definition has correct name, parameters, permissions
- `testExecute_missingPath` -- Returns validation error
- `testExecute_missingPage` -- Returns validation error
- `testExecute_fileNotFound` -- Returns file_not_found error
- `testExecute_pageOutOfRange` -- Returns invalid_page error
- `testExecute_dpiClamping` -- DPI values outside 72-300 are clamped

### Test PDF Resources

Place test PDF files in `app/src/test/resources/`:
- `test-document.pdf` -- A simple text-based PDF with known content (2-3 pages)
- Created programmatically in test setup using PDFBox, or checked in as a small test fixture

### Integration Tests (Manual)

1. Install app on device with PDFs in `/sdcard/Documents/`
2. Ask agent to summarize a text-based PDF
3. Ask agent to render a page from a scanned PDF
4. Ask agent to extract specific pages from a long document
5. Verify tool calls appear correctly in chat history

## Alternatives Considered

### 1. Single PdfTool class with mode parameter

**Approach**: One `PdfTool` class with a `mode` parameter ("info", "extract_text", "render_page"), similar to `BrowserTool`.

**Rejected because**: The three operations have very different parameter sets. Combining them into one tool with a mode parameter would make the parameter schema complex and confusing for AI models. Separate tools with focused parameter schemas produce better AI tool-calling behavior.

### 2. JavaScript-based PDF tools

**Approach**: Implement PDF tools as JS tools using a PDF library in QuickJS.

**Rejected because**: QuickJS has no DOM APIs and limited file I/O. PDF parsing libraries require native code or full JVM support. PDFBox Android and PdfRenderer are Kotlin/Java native and cannot run in QuickJS. The Kotlin built-in approach is the correct fit.

### 3. Use only Android PdfRenderer (no PDFBox)

**Approach**: Use Android's built-in PdfRenderer for everything, including text extraction (by rendering pages and using OCR).

**Rejected because**: PdfRenderer can only render pages as bitmaps. It cannot extract text directly. Using OCR for text extraction would be slow, inaccurate, and add unnecessary complexity. PDFBox provides direct text extraction via PDFTextStripper.

## Dependencies

### External Dependencies

| Dependency | Version | Size | License |
|------------|---------|------|---------|
| com.tom-roush:pdfbox-android | 2.0.27.0 | ~2.5MB | Apache 2.0 |
| Android PdfRenderer | Built-in (API 21+) | 0 | Android Framework |

### Internal Dependencies

- `Tool` interface from `tool/engine/`
- `ToolResult`, `ToolDefinition`, `ToolParametersSchema`, `ToolParameter` from `core/model/`
- `ToolRegistry`, `ToolSourceInfo` from `tool/engine/` and `core/model/`
- `Context` from Android framework (injected via Koin `androidContext()`)

## Future Extensions

- **Password-protected PDFs**: Add optional `password` parameter to `pdf_extract_text` and `pdf_info`
- **Batch page rendering**: Add `pages` parameter to `pdf_render_page` to render multiple pages at once
- **PDF search**: Add a `pdf_search` tool to search for text within a PDF and return page numbers and context
- **PDF table extraction**: Specialized table extraction using PDFBox's table detection heuristics
- **PDF bookmark/outline extraction**: Extract the document outline/table of contents

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
