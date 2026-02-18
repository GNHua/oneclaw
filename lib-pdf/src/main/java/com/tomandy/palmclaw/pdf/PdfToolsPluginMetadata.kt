package com.tomandy.palmclaw.pdf

import com.tomandy.palmclaw.engine.PluginMetadata
import com.tomandy.palmclaw.engine.ToolDefinition
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object PdfToolsPluginMetadata {

    fun get(): PluginMetadata {
        return PluginMetadata(
            id = "pdf-tools",
            name = "PDF Tools",
            version = "1.0.0",
            description = "Extract text, metadata, and render pages from PDF files in the workspace",
            author = "PalmClaw",
            entryPoint = "PdfToolsPlugin",
            tools = listOf(pdfInfoTool(), pdfExtractTextTool(), pdfRenderPageTool()),
            category = "pdf"
        )
    }

    private fun pdfInfoTool() = ToolDefinition(
        name = "pdf_info",
        description = """Get metadata and info about a PDF file.
            |Returns page count, file size, title, author, and other document properties.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive("Relative path to the PDF file in the workspace")
                    )
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("path"))
            }
        }
    )

    private fun pdfExtractTextTool() = ToolDefinition(
        name = "pdf_extract_text",
        description = """Extract text content from a PDF file.
            |Supports page range selection. For scanned PDFs with no text layer,
            |use pdf_render_page to get page images instead.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive("Relative path to the PDF file in the workspace")
                    )
                }
                putJsonObject("pages") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive(
                            "Page range to extract (e.g. \"1-5\", \"3\", \"1,3,5-7\"). " +
                                "Omit to extract all pages."
                        )
                    )
                }
                putJsonObject("max_chars") {
                    put("type", JsonPrimitive("integer"))
                    put(
                        "description",
                        JsonPrimitive("Maximum characters to return (default 50000)")
                    )
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("path"))
            }
        }
    )

    private fun pdfRenderPageTool() = ToolDefinition(
        name = "pdf_render_page",
        description = """Render a PDF page to a PNG image in the workspace.
            |Useful for scanned PDFs or pages with complex layouts, charts, or images.
            |The rendered image is saved to pdf-renders/ in the workspace.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive("Relative path to the PDF file in the workspace")
                    )
                }
                putJsonObject("page") {
                    put("type", JsonPrimitive("integer"))
                    put(
                        "description",
                        JsonPrimitive("Page number to render (1-based)")
                    )
                }
                putJsonObject("dpi") {
                    put("type", JsonPrimitive("integer"))
                    put(
                        "description",
                        JsonPrimitive("Render resolution in DPI (default 150, min 72, max 300)")
                    )
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("path"))
                add(JsonPrimitive("page"))
            }
        }
    )
}
