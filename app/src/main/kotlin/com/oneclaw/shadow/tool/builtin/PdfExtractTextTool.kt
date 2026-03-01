package com.oneclaw.shadow.tool.builtin

import android.content.Context
import android.util.Log
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.tool.engine.Tool
import com.oneclaw.shadow.tool.util.PdfToolUtils
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

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
        requiredPermissions = listOf("android.permission.READ_EXTERNAL_STORAGE"),
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
