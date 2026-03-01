package com.oneclaw.shadow.tool.builtin

import android.content.Context
import android.util.Log
import com.oneclaw.shadow.core.model.ToolResultStatus
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class PdfExtractTextToolTest {

    private lateinit var context: Context
    private lateinit var tool: PdfExtractTextTool
    private lateinit var tempDir: File

    @BeforeEach
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0

        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        tool = PdfExtractTextTool(context)
        tempDir = createTempDir("pdf_extract_test")
    }

    @AfterEach
    fun teardown() {
        unmockkStatic(Log::class)
        tempDir.deleteRecursively()
    }

    /**
     * Creates a valid PDF with empty pages using PDFBox.
     * Pages have no text content (no fonts required).
     */
    private fun createEmptyPdf(fileName: String, pages: Int = 1): File {
        val file = File(tempDir, fileName)
        val doc = PDDocument()
        repeat(pages) { doc.addPage(PDPage()) }
        doc.save(file)
        doc.close()
        return file
    }

    @Test
    fun testDefinition() {
        val def = tool.definition
        assertEquals("pdf_extract_text", def.name)
        assertTrue(def.parametersSchema.required.contains("path"))
        assertFalse(def.parametersSchema.required.contains("pages"))
        assertFalse(def.parametersSchema.required.contains("max_chars"))
        assertTrue(def.parametersSchema.properties.containsKey("path"))
        assertTrue(def.parametersSchema.properties.containsKey("pages"))
        assertTrue(def.parametersSchema.properties.containsKey("max_chars"))
        assertTrue(def.requiredPermissions.contains("android.permission.READ_EXTERNAL_STORAGE"))
        assertEquals(30, def.timeoutSeconds)
    }

    @Test
    fun testExecute_missingPath() = runTest {
        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("path", ignoreCase = true))
    }

    @Test
    fun testExecute_fileNotFound() = runTest {
        val result = tool.execute(mapOf("path" to "/nonexistent/path/file.pdf"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("file_not_found", result.errorType)
        assertTrue(result.errorMessage!!.contains("File not found"))
    }

    @Test
    fun testExecute_extractAllPages() = runTest {
        // Empty PDF returns "no text found" successfully
        val pdfFile = createEmptyPdf("text.pdf", pages = 3)

        val result = tool.execute(mapOf("path" to pdfFile.absolutePath))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        // Empty PDF returns no-text message
        assertTrue(
            result.result!!.contains("No text content found"),
            "Empty PDF should report no text: ${result.result}"
        )
    }

    @Test
    fun testExecute_extractPageRange() = runTest {
        val pdfFile = createEmptyPdf("range.pdf", pages = 3)

        val result = tool.execute(mapOf("path" to pdfFile.absolutePath, "pages" to "1-2"))

        // Valid page range on empty PDF returns success
        assertEquals(ToolResultStatus.SUCCESS, result.status)
    }

    @Test
    fun testExecute_invalidPageRange() = runTest {
        val pdfFile = createEmptyPdf("range.pdf", pages = 2)

        val result = tool.execute(mapOf("path" to pdfFile.absolutePath, "pages" to "5-10"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("invalid_page_range", result.errorType)
        assertTrue(result.errorMessage!!.contains("Invalid page range"))
    }

    @Test
    fun testExecute_invalidPageRange_reversed() = runTest {
        val pdfFile = createEmptyPdf("range.pdf", pages = 5)

        val result = tool.execute(mapOf("path" to pdfFile.absolutePath, "pages" to "5-2"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("invalid_page_range", result.errorType)
    }

    @Test
    fun testExecute_invalidPageRange_nonNumeric() = runTest {
        val pdfFile = createEmptyPdf("range.pdf", pages = 5)

        val result = tool.execute(mapOf("path" to pdfFile.absolutePath, "pages" to "abc"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("invalid_page_range", result.errorType)
    }

    @Test
    fun testExecute_defaultMaxChars() = runTest {
        // Verify the tool definition description mentions 50000
        val def = tool.definition
        assertTrue(
            def.parametersSchema.properties["max_chars"]!!.description.contains("50000"),
            "max_chars description should mention default 50000"
        )
    }

    @Test
    fun testExecute_emptyPdf() = runTest {
        val pdfFile = createEmptyPdf("empty.pdf")

        val result = tool.execute(mapOf("path" to pdfFile.absolutePath))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(
            result.result!!.contains("No text content found"),
            "Should indicate no text found for empty PDF: ${result.result}"
        )
    }

    @Test
    fun testExecute_singlePage_emptyPdf() = runTest {
        val pdfFile = createEmptyPdf("single.pdf", pages = 1)

        val result = tool.execute(mapOf("path" to pdfFile.absolutePath, "pages" to "1"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
    }

    @Test
    fun testExecute_pageBeyondDocument() = runTest {
        val pdfFile = createEmptyPdf("small.pdf", pages = 2)

        val result = tool.execute(mapOf("path" to pdfFile.absolutePath, "pages" to "3"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("invalid_page_range", result.errorType)
    }

    @Test
    fun testExecute_validSinglePageRange() = runTest {
        val pdfFile = createEmptyPdf("multi.pdf", pages = 5)

        val result = tool.execute(mapOf("path" to pdfFile.absolutePath, "pages" to "3"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
    }
}
