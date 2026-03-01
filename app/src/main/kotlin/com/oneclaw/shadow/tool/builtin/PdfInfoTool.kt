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
import java.io.File

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
        requiredPermissions = listOf("android.permission.READ_EXTERNAL_STORAGE"),
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
