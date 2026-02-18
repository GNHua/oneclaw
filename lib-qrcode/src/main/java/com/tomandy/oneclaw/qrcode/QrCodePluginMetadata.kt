package com.tomandy.oneclaw.qrcode

import com.tomandy.oneclaw.engine.PluginMetadata
import com.tomandy.oneclaw.engine.ToolDefinition
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object QrCodePluginMetadata {

    fun get(): PluginMetadata {
        return PluginMetadata(
            id = "qrcode",
            name = "QR Code",
            version = "1.0.0",
            description = "Scan QR codes/barcodes from images and generate QR code images",
            author = "OneClaw",
            entryPoint = "QrCodePlugin",
            tools = listOf(qrScanTool(), qrGenerateTool()),
            category = "core"
        )
    }

    private fun qrScanTool() = ToolDefinition(
        name = "qr_scan",
        description = """Scan and decode QR codes or barcodes from an image file.
            |
            |Supports all common formats: QR Code, EAN-13, UPC-A, Code 128,
            |Data Matrix, PDF417, Aztec, and more.
            |Returns the decoded value and format for each code found.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("image_path") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive("Relative path to the image file in the workspace")
                    )
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("image_path"))
            }
        }
    )

    private fun qrGenerateTool() = ToolDefinition(
        name = "qr_generate",
        description = """Generate a QR code image from text content.
            |
            |Creates a PNG image of the QR code and saves it to the workspace.
            |Can encode URLs, text, contact info, WiFi credentials, or any string.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("content") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive("The text or data to encode in the QR code")
                    )
                }
                putJsonObject("size") {
                    put("type", JsonPrimitive("integer"))
                    put(
                        "description",
                        JsonPrimitive("Image size in pixels (default 512, min 64, max 2048)")
                    )
                }
                putJsonObject("output_path") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive("Output file path in workspace (default: images/qr-{timestamp}.png)")
                    )
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("content"))
            }
        }
    )
}
