package com.oneclaw.shadow.tool.builtin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.Log
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.tool.engine.Tool
import java.io.File
import java.io.FileOutputStream

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
        requiredPermissions = listOf("android.permission.READ_EXTERNAL_STORAGE"),
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
