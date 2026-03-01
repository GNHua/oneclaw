package com.oneclaw.shadow.tool.builtin

import android.content.Context
import android.util.Log
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.tool.engine.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Kotlin built-in tool that executes shell commands on the Android device
 * using Runtime.getRuntime().exec(). Captures stdout, stderr, and exit code.
 *
 * RFC-029: Shell Exec Tool
 */
class ExecTool(
    private val context: Context
) : Tool {

    companion object {
        private const val TAG = "ExecTool"
        private const val DEFAULT_TIMEOUT_SECONDS = 30
        private const val MAX_TIMEOUT_SECONDS = 120
        private const val DEFAULT_MAX_LENGTH = 50_000
    }

    override val definition = ToolDefinition(
        name = "exec",
        description = "Execute a shell command on the device and return its output",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "command" to ToolParameter(
                    type = "string",
                    description = "The shell command to execute"
                ),
                "timeout_seconds" to ToolParameter(
                    type = "integer",
                    description = "Maximum execution time in seconds. Default: 30, Max: 120"
                ),
                "working_directory" to ToolParameter(
                    type = "string",
                    description = "Working directory for the command. Default: app data directory"
                ),
                "max_length" to ToolParameter(
                    type = "integer",
                    description = "Maximum output length in characters. Default: 50000"
                )
            ),
            required = listOf("command")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = MAX_TIMEOUT_SECONDS + 5  // Extra buffer beyond process timeout
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        // 1. Parse and validate parameters
        val command = parameters["command"]?.toString()?.trim()
        if (command.isNullOrBlank()) {
            return ToolResult.error(
                "validation_error",
                "Parameter 'command' is required and cannot be empty"
            )
        }

        val timeoutSeconds = parseIntParam(parameters["timeout_seconds"])
            ?.coerceIn(1, MAX_TIMEOUT_SECONDS)
            ?: DEFAULT_TIMEOUT_SECONDS

        val maxLength = parseIntParam(parameters["max_length"])
            ?.coerceAtLeast(1)
            ?: DEFAULT_MAX_LENGTH

        val workingDir = parameters["working_directory"]?.toString()?.let { path ->
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) {
                return ToolResult.error(
                    "validation_error",
                    "Working directory does not exist: $path"
                )
            }
            dir
        } ?: context.filesDir

        // 2. Execute the command
        return try {
            executeCommand(command, workingDir, timeoutSeconds, maxLength)
        } catch (e: SecurityException) {
            ToolResult.error("permission_error", "Permission denied: ${e.message}")
        } catch (e: IOException) {
            ToolResult.error("execution_error", "Failed to start process: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error executing command", e)
            ToolResult.error("execution_error", "Error: ${e.message}")
        }
    }

    private suspend fun executeCommand(
        command: String,
        workingDir: File,
        timeoutSeconds: Int,
        maxLength: Int
    ): ToolResult = withContext(Dispatchers.IO) {
        val process = Runtime.getRuntime().exec(
            arrayOf("sh", "-c", command),
            null,  // inherit environment
            workingDir
        )

        try {
            // 3. Capture stdout and stderr concurrently
            val stdoutDeferred = async {
                readStream(process.inputStream, maxLength)
            }
            val stderrDeferred = async {
                readStream(process.errorStream, maxLength)
            }

            // 4. Wait for process completion with timeout
            val completed = process.waitFor(
                timeoutSeconds.toLong(),
                TimeUnit.SECONDS
            )

            if (!completed) {
                // Timeout: kill the process
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)  // Brief wait for cleanup

                val stdout = stdoutDeferred.await()
                val stderr = stderrDeferred.await()

                return@withContext ToolResult.success(
                    formatOutput(
                        exitCode = -1,
                        stdout = stdout,
                        stderr = stderr,
                        timedOut = true,
                        timeoutSeconds = timeoutSeconds
                    )
                )
            }

            val exitCode = process.exitValue()
            val stdout = stdoutDeferred.await()
            val stderr = stderrDeferred.await()

            ToolResult.success(
                formatOutput(
                    exitCode = exitCode,
                    stdout = stdout,
                    stderr = stderr,
                    timedOut = false,
                    timeoutSeconds = timeoutSeconds
                )
            )
        } finally {
            process.destroy()
        }
    }

    /**
     * Read an InputStream into a String, truncating at maxLength.
     */
    private fun readStream(stream: InputStream, maxLength: Int): String {
        val reader = BufferedReader(InputStreamReader(stream))
        val sb = StringBuilder()
        var totalRead = 0

        reader.use {
            val buffer = CharArray(8192)
            while (true) {
                val count = reader.read(buffer)
                if (count == -1) break

                val remaining = maxLength - totalRead
                if (remaining <= 0) break

                val toAppend = minOf(count, remaining)
                sb.append(buffer, 0, toAppend)
                totalRead += toAppend

                if (totalRead >= maxLength) break
            }
        }

        return sb.toString()
    }

    /**
     * Format the output with exit code, stdout, stderr, and timeout info.
     */
    private fun formatOutput(
        exitCode: Int,
        stdout: String,
        stderr: String,
        timedOut: Boolean,
        timeoutSeconds: Int
    ): String {
        val sb = StringBuilder()

        if (timedOut) {
            sb.appendLine("[Exit Code: -1 (timeout after ${timeoutSeconds}s)]")
        } else {
            sb.appendLine("[Exit Code: $exitCode]")
        }

        if (stdout.isNotEmpty()) {
            sb.appendLine()
            sb.append(stdout)
            if (!stdout.endsWith("\n")) sb.appendLine()
        }

        if (stderr.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("[stderr]")
            sb.append(stderr)
            if (!stderr.endsWith("\n")) sb.appendLine()
        }

        if (timedOut) {
            if (stderr.isEmpty()) {
                sb.appendLine()
                sb.appendLine("[stderr]")
            }
            sb.appendLine("Process killed after ${timeoutSeconds} seconds timeout.")
        }

        if (stdout.isEmpty() && stderr.isEmpty() && !timedOut) {
            sb.appendLine()
            sb.appendLine("(no output)")
        }

        return sb.toString().trimEnd()
    }

    private fun parseIntParam(value: Any?): Int? {
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Double -> value.toInt()
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }
}
