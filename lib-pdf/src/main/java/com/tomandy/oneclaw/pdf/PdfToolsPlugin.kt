package com.tomandy.oneclaw.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tomandy.oneclaw.engine.Plugin
import com.tomandy.oneclaw.engine.PluginContext
import com.tomandy.oneclaw.engine.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream

class PdfToolsPlugin : Plugin {

    private lateinit var workspaceRoot: File
    private var initialized = false

    override suspend fun onLoad(context: PluginContext) {
        workspaceRoot = File(
            context.getApplicationContext().filesDir, "workspace"
        ).also { it.mkdirs() }

        if (!initialized) {
            PDFBoxResourceLoader.init(context.getApplicationContext())
            initialized = true
        }
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        return when (toolName) {
            "pdf_info" -> pdfInfo(arguments)
            "pdf_extract_text" -> pdfExtractText(arguments)
            "pdf_render_page" -> pdfRenderPage(arguments)
            else -> ToolResult.Failure("Unknown tool: $toolName")
        }
    }

    override suspend fun onUnload() {}

    private fun pdfInfo(arguments: JsonObject): ToolResult {
        val path = arguments["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: path")

        val file = resolveSafePath(path)
        if (!file.exists()) {
            return ToolResult.Failure("File not found: $path")
        }

        return try {
            val doc = PDDocument.load(file)
            val result = try {
                val info = doc.documentInformation
                val lines = mutableListOf<String>()
                lines.add("File: $path")
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

                ToolResult.Success(lines.joinToString("\n"))
            } finally {
                doc.close()
            }
            result
        } catch (e: Exception) {
            ToolResult.Failure("Failed to read PDF info: ${e.message}", e)
        }
    }

    private fun pdfExtractText(arguments: JsonObject): ToolResult {
        val path = arguments["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: path")
        val maxChars = arguments["max_chars"]?.jsonPrimitive?.intOrNull ?: 50000

        val file = resolveSafePath(path)
        if (!file.exists()) {
            return ToolResult.Failure("File not found: $path")
        }

        return try {
            val doc = PDDocument.load(file)
            val result = try {
                val stripper = PDFTextStripper()
                val totalPages = doc.numberOfPages

                val pagesArg = arguments["pages"]?.jsonPrimitive?.content
                if (pagesArg != null) {
                    val range = parsePageRange(pagesArg, totalPages)
                    if (range == null) {
                        return ToolResult.Failure(
                            "Invalid page range: $pagesArg (document has $totalPages pages)"
                        )
                    }
                    stripper.startPage = range.first
                    stripper.endPage = range.second
                }

                val text = stripper.getText(doc)

                if (text.isBlank()) {
                    ToolResult.Success(
                        "No text content found in PDF. This may be a scanned document. " +
                            "Use pdf_render_page to render pages as images for visual inspection."
                    )
                } else {
                    val truncated = if (text.length > maxChars) {
                        text.take(maxChars) + "\n\n[Truncated at $maxChars characters. " +
                            "Total text length: ${text.length}. " +
                            "Use 'pages' parameter to extract specific pages.]"
                    } else {
                        text
                    }

                    val header = "Extracted text from $path" +
                        (if (pagesArg != null) " (pages: $pagesArg)" else "") +
                        " [${totalPages} total pages]:\n\n"

                    ToolResult.Success(header + truncated)
                }
            } finally {
                doc.close()
            }
            result
        } catch (e: Exception) {
            ToolResult.Failure("Failed to extract PDF text: ${e.message}", e)
        }
    }

    private fun pdfRenderPage(arguments: JsonObject): ToolResult {
        val path = arguments["path"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: path")
        val pageNum = arguments["page"]?.jsonPrimitive?.intOrNull
            ?: return ToolResult.Failure("Missing required field: page")
        val dpi = (arguments["dpi"]?.jsonPrimitive?.intOrNull ?: 150).coerceIn(72, 300)

        val file = resolveSafePath(path)
        if (!file.exists()) {
            return ToolResult.Failure("File not found: $path")
        }

        return try {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(fd)

            val pageIndex = pageNum - 1
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
                renderer.close()
                fd.close()
                return ToolResult.Failure(
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

            val outputDir = File(workspaceRoot, "pdf-renders").also { it.mkdirs() }
            val baseName = file.nameWithoutExtension
            val outputFile = File(outputDir, "${baseName}-page${pageNum}.png")
            FileOutputStream(outputFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            bitmap.recycle()

            val relativePath = outputFile.relativeTo(workspaceRoot).path
            ToolResult.Success(
                "Page $pageNum rendered and saved to: $relativePath\n" +
                    "Resolution: ${width}x${height} (${dpi} DPI)\n" +
                    "File size: ${outputFile.length()} bytes"
            )
        } catch (e: Exception) {
            ToolResult.Failure("Failed to render PDF page: ${e.message}", e)
        }
    }

    private fun parsePageRange(spec: String, totalPages: Int): Pair<Int, Int>? {
        val trimmed = spec.trim()

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

        if (trimmed.contains("-")) {
            val parts = trimmed.split("-", limit = 2)
            val start = parts[0].trim().toIntOrNull() ?: return null
            val end = parts[1].trim().toIntOrNull() ?: return null
            if (start < 1 || end > totalPages || start > end) return null
            return Pair(start, end)
        }

        val page = trimmed.toIntOrNull() ?: return null
        if (page < 1 || page > totalPages) return null
        return Pair(page, page)
    }

    private fun resolveSafePath(relativePath: String): File {
        if (relativePath.startsWith("/")) {
            throw SecurityException("Absolute paths not allowed: $relativePath")
        }
        val resolved = File(workspaceRoot, relativePath).canonicalFile
        if (!resolved.path.startsWith(workspaceRoot.canonicalPath)) {
            throw SecurityException("Path traversal not allowed: $relativePath")
        }
        return resolved
    }
}
