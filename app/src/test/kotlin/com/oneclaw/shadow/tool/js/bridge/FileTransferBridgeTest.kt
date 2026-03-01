package com.oneclaw.shadow.tool.js.bridge

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Unit tests for FileTransferBridge.
 *
 * Tests the download and upload logic using MockWebServer.
 * QuickJS injection is not tested here (requires native JNI libraries).
 */
class FileTransferBridgeTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var mockWebServer: MockWebServer
    private lateinit var okHttpClient: OkHttpClient

    @BeforeEach
    fun setUp() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        okHttpClient = OkHttpClient()
    }

    @AfterEach
    fun tearDown() {
        mockWebServer.shutdown()
    }

    // --- Tests for FILE_TRANSFER_WRAPPER_JS ---

    @Test
    fun `FILE_TRANSFER_WRAPPER_JS is not null or blank`() {
        val js = FileTransferBridge.FILE_TRANSFER_WRAPPER_JS
        assertNotNull(js)
        assertTrue(js.isNotBlank())
    }

    @Test
    fun `FILE_TRANSFER_WRAPPER_JS declares downloadToFile function`() {
        val js = FileTransferBridge.FILE_TRANSFER_WRAPPER_JS
        assertTrue(js.contains("async function downloadToFile"), "Should declare async downloadToFile")
    }

    @Test
    fun `FILE_TRANSFER_WRAPPER_JS declares uploadMultipart function`() {
        val js = FileTransferBridge.FILE_TRANSFER_WRAPPER_JS
        assertTrue(js.contains("async function uploadMultipart"), "Should declare async uploadMultipart")
    }

    @Test
    fun `FILE_TRANSFER_WRAPPER_JS calls native __downloadToFile`() {
        val js = FileTransferBridge.FILE_TRANSFER_WRAPPER_JS
        assertTrue(js.contains("__downloadToFile"), "Should call __downloadToFile native function")
    }

    @Test
    fun `FILE_TRANSFER_WRAPPER_JS calls native __uploadMultipart`() {
        val js = FileTransferBridge.FILE_TRANSFER_WRAPPER_JS
        assertTrue(js.contains("__uploadMultipart"), "Should call __uploadMultipart native function")
    }

    @Test
    fun `FILE_TRANSFER_WRAPPER_JS parses result as JSON`() {
        val js = FileTransferBridge.FILE_TRANSFER_WRAPPER_JS
        assertTrue(js.contains("JSON.parse"), "Should parse result as JSON")
    }

    @Test
    fun `FILE_TRANSFER_WRAPPER_JS has balanced braces`() {
        val js = FileTransferBridge.FILE_TRANSFER_WRAPPER_JS
        val openBraces = js.count { it == '{' }
        val closeBraces = js.count { it == '}' }
        assertEquals(openBraces, closeBraces)
    }

    // --- Tests for performDownload ---

    @Test
    fun `performDownload saves file to correct path on success`() = runTest {
        val fileContent = "Hello, Drive!"
        mockWebServer.enqueue(MockResponse()
            .setBody(fileContent)
            .setResponseCode(200))

        val savePath = File(tempDir, "downloaded.txt").absolutePath
        val url = mockWebServer.url("/file/123?alt=media").toString()

        val resultJson = FileTransferBridge.performDownload(
            okHttpClient, url, savePath, "{}"
        )

        val result = Json.parseToJsonElement(resultJson).jsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean, "Download should succeed")
        assertEquals(savePath, result["path"]!!.jsonPrimitive.content)

        // Verify file content
        val savedFile = File(savePath)
        assertTrue(savedFile.exists(), "File should exist at save path")
        assertEquals(fileContent, savedFile.readText())
    }

    @Test
    fun `performDownload returns error result on HTTP error`() = runTest {
        mockWebServer.enqueue(MockResponse()
            .setBody("Not Found")
            .setResponseCode(404))

        val savePath = File(tempDir, "should-not-exist.txt").absolutePath
        val url = mockWebServer.url("/missing").toString()

        val resultJson = FileTransferBridge.performDownload(
            okHttpClient, url, savePath, "{}"
        )

        val result = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(result["success"]!!.jsonPrimitive.boolean, "Download should fail")
        assertTrue(result.containsKey("error"), "Result should contain error message")
        val errorMessage = result["error"]!!.jsonPrimitive.content
        assertTrue(errorMessage.contains("404"), "Error should mention HTTP 404")
    }

    @Test
    fun `performDownload sends custom headers`() = runTest {
        mockWebServer.enqueue(MockResponse()
            .setBody("content")
            .setResponseCode(200))

        val savePath = File(tempDir, "auth-file.txt").absolutePath
        val url = mockWebServer.url("/file").toString()
        val headersJson = """{"Authorization": "Bearer test-token", "X-Custom": "value"}"""

        FileTransferBridge.performDownload(okHttpClient, url, savePath, headersJson)

        val request = mockWebServer.takeRequest()
        assertEquals("Bearer test-token", request.getHeader("Authorization"))
        assertEquals("value", request.getHeader("X-Custom"))
    }

    @Test
    fun `performDownload creates parent directories`() = runTest {
        mockWebServer.enqueue(MockResponse()
            .setBody("content")
            .setResponseCode(200))

        val nestedPath = File(tempDir, "subdir/nested/file.txt").absolutePath
        val url = mockWebServer.url("/file").toString()

        val resultJson = FileTransferBridge.performDownload(
            okHttpClient, url, nestedPath, "{}"
        )

        val result = Json.parseToJsonElement(resultJson).jsonObject
        assertTrue(result["success"]!!.jsonPrimitive.boolean)
        assertTrue(File(nestedPath).exists(), "File should be created in nested directory")
    }

    @Test
    fun `performDownload returns error result on network failure`() = runTest {
        // Use an invalid URL to simulate network failure
        val savePath = File(tempDir, "fail.txt").absolutePath
        val resultJson = FileTransferBridge.performDownload(
            okHttpClient, "http://invalid-host-that-does-not-exist-xyz.invalid/file", savePath, "{}"
        )

        val result = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(result["success"]!!.jsonPrimitive.boolean)
        assertTrue(result.containsKey("error"))
    }

    // --- Tests for performUpload ---

    @Test
    fun `performUpload sends JSON and file parts`() = runTest {
        val responseBody = """{"id": "new-file-id", "name": "test.txt"}"""
        mockWebServer.enqueue(MockResponse()
            .setBody(responseBody)
            .setResponseCode(200))

        val testFile = File(tempDir, "upload.txt")
        testFile.writeText("file content to upload")

        val url = mockWebServer.url("/upload/drive/v3/files").toString()
        val partsJson = """
            [
                {"type": "json", "contentType": "application/json", "body": "{\"name\": \"test.txt\"}"},
                {"type": "file", "contentType": "text/plain", "path": "${testFile.absolutePath}"}
            ]
        """.trimIndent()

        val resultJson = FileTransferBridge.performUpload(
            okHttpClient, url, partsJson, "{}"
        )

        val result = Json.parseToJsonElement(resultJson).jsonObject
        assertTrue(result["ok"]!!.jsonPrimitive.boolean, "Upload should succeed")
        assertEquals(200, result["status"]!!.jsonPrimitive.int)
        assertEquals(responseBody, result["body"]!!.jsonPrimitive.content)
    }

    @Test
    fun `performUpload sends custom authorization header`() = runTest {
        mockWebServer.enqueue(MockResponse()
            .setBody("{}")
            .setResponseCode(200))

        val testFile = File(tempDir, "upload.txt")
        testFile.writeText("content")

        val url = mockWebServer.url("/upload").toString()
        val partsJson = """[{"type": "file", "contentType": "text/plain", "path": "${testFile.absolutePath}"}]"""
        val headersJson = """{"Authorization": "Bearer drive-token"}"""

        FileTransferBridge.performUpload(okHttpClient, url, partsJson, headersJson)

        val request = mockWebServer.takeRequest()
        assertEquals("Bearer drive-token", request.getHeader("Authorization"))
    }

    @Test
    fun `performUpload returns error result when file not found`() = runTest {
        val url = mockWebServer.url("/upload").toString()
        val partsJson = """[{"type": "file", "contentType": "application/octet-stream", "path": "/nonexistent/path/file.bin"}]"""

        val resultJson = FileTransferBridge.performUpload(
            okHttpClient, url, partsJson, "{}"
        )

        val result = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(result["ok"]!!.jsonPrimitive.boolean)
        assertTrue(result.containsKey("error"))
    }

    @Test
    fun `performUpload returns unsuccessful result on HTTP error`() = runTest {
        mockWebServer.enqueue(MockResponse()
            .setBody("Forbidden")
            .setResponseCode(403))

        val testFile = File(tempDir, "upload.txt")
        testFile.writeText("content")

        val url = mockWebServer.url("/upload").toString()
        val partsJson = """[{"type": "file", "contentType": "text/plain", "path": "${testFile.absolutePath}"}]"""

        val resultJson = FileTransferBridge.performUpload(
            okHttpClient, url, partsJson, "{}"
        )

        val result = Json.parseToJsonElement(resultJson).jsonObject
        assertFalse(result["ok"]!!.jsonPrimitive.boolean)
        assertEquals(403, result["status"]!!.jsonPrimitive.int)
    }

    @Test
    fun `performUpload handles JSON part only`() = runTest {
        mockWebServer.enqueue(MockResponse()
            .setBody("{\"success\": true}")
            .setResponseCode(200))

        val url = mockWebServer.url("/api").toString()
        val partsJson = """[{"type": "json", "contentType": "application/json", "body": "{\"key\": \"value\"}"}]"""

        val resultJson = FileTransferBridge.performUpload(
            okHttpClient, url, partsJson, "{}"
        )

        val result = Json.parseToJsonElement(resultJson).jsonObject
        assertTrue(result["ok"]!!.jsonPrimitive.boolean)
    }
}
