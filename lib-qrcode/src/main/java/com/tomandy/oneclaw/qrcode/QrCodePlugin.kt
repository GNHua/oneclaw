package com.tomandy.oneclaw.qrcode

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.tomandy.oneclaw.engine.Plugin
import com.tomandy.oneclaw.engine.PluginContext
import com.tomandy.oneclaw.engine.ToolResult
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream

class QrCodePlugin : Plugin {

    private lateinit var workspaceRoot: File

    override suspend fun onLoad(context: PluginContext) {
        workspaceRoot = File(
            context.getApplicationContext().filesDir, "workspace"
        ).also { it.mkdirs() }
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        return when (toolName) {
            "qr_scan" -> qrScan(arguments)
            "qr_generate" -> qrGenerate(arguments)
            else -> ToolResult.Failure("Unknown tool: $toolName")
        }
    }

    override suspend fun onUnload() {}

    private suspend fun qrScan(arguments: JsonObject): ToolResult {
        val imagePath = arguments["image_path"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: image_path")

        val file = resolveSafePath(imagePath)
        if (!file.exists()) {
            return ToolResult.Failure("File not found: $imagePath")
        }

        return try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                ?: return ToolResult.Failure("Could not decode image: $imagePath")

            val image = InputImage.fromBitmap(bitmap, 0)
            val options = BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS)
                .build()
            val scanner = BarcodeScanning.getClient(options)

            val barcodes = scanner.process(image).await()

            if (barcodes.isEmpty()) {
                return ToolResult.Success("No QR codes or barcodes found in the image.")
            }

            val results = barcodes.mapIndexed { index, barcode ->
                val format = formatName(barcode.format)
                val value = barcode.rawValue ?: barcode.displayValue ?: "(no value)"
                val typeStr = typeName(barcode.valueType)
                "Result ${index + 1}:\n  Format: $format\n  Type: $typeStr\n  Value: $value"
            }

            ToolResult.Success(
                "Found ${barcodes.size} code(s):\n\n${results.joinToString("\n\n")}"
            )
        } catch (e: Exception) {
            ToolResult.Failure("Failed to scan QR code: ${e.message}", e)
        }
    }

    private fun qrGenerate(arguments: JsonObject): ToolResult {
        val content = arguments["content"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: content")
        val size = (arguments["size"]?.jsonPrimitive?.intOrNull ?: 512).coerceIn(64, 2048)
        val outputPath = arguments["output_path"]?.jsonPrimitive?.content
            ?: "images/qr-${System.currentTimeMillis()}.png"

        return try {
            val hints = mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val bitMatrix = QRCodeWriter().encode(
                content, BarcodeFormat.QR_CODE, size, size, hints
            )

            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
                }
            }

            val file = resolveSafePath(outputPath)
            file.parentFile?.mkdirs()
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }

            val fileSize = file.length()
            ToolResult.Success(
                "QR code generated and saved to: $outputPath\n" +
                    "Size: ${size}x${size}\n" +
                    "File size: $fileSize bytes\n" +
                    "Content: ${content.take(100)}${if (content.length > 100) "..." else ""}"
            )
        } catch (e: Exception) {
            ToolResult.Failure("Failed to generate QR code: ${e.message}", e)
        }
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

    private fun formatName(format: Int): String = when (format) {
        Barcode.FORMAT_QR_CODE -> "QR Code"
        Barcode.FORMAT_AZTEC -> "Aztec"
        Barcode.FORMAT_CODABAR -> "Codabar"
        Barcode.FORMAT_CODE_39 -> "Code 39"
        Barcode.FORMAT_CODE_93 -> "Code 93"
        Barcode.FORMAT_CODE_128 -> "Code 128"
        Barcode.FORMAT_DATA_MATRIX -> "Data Matrix"
        Barcode.FORMAT_EAN_8 -> "EAN-8"
        Barcode.FORMAT_EAN_13 -> "EAN-13"
        Barcode.FORMAT_ITF -> "ITF"
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_UPC_A -> "UPC-A"
        Barcode.FORMAT_UPC_E -> "UPC-E"
        else -> "Unknown ($format)"
    }

    private fun typeName(type: Int): String = when (type) {
        Barcode.TYPE_URL -> "URL"
        Barcode.TYPE_TEXT -> "Text"
        Barcode.TYPE_EMAIL -> "Email"
        Barcode.TYPE_PHONE -> "Phone"
        Barcode.TYPE_SMS -> "SMS"
        Barcode.TYPE_WIFI -> "WiFi"
        Barcode.TYPE_GEO -> "Geo"
        Barcode.TYPE_CONTACT_INFO -> "Contact"
        Barcode.TYPE_CALENDAR_EVENT -> "Calendar Event"
        Barcode.TYPE_ISBN -> "ISBN"
        Barcode.TYPE_PRODUCT -> "Product"
        else -> "Unknown"
    }
}
