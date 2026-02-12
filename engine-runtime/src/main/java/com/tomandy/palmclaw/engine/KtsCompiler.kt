package com.tomandy.palmclaw.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Compiles Kotlin Script (.kts) source code to JVM bytecode (.jar).
 *
 * **Current Implementation**: Simplified for Phase 2 infrastructure testing.
 * Full kotlin-scripting integration requires complex API usage that varies by version.
 *
 * **TODO**: Implement full KTS compilation using kotlin-scripting-jvm-host API.
 * For now, this creates marker JAR files to enable end-to-end testing of the
 * plugin loading pipeline (KtsCompiler → DexConverter → PluginLoader).
 *
 * Performance:
 * - Target: ~2-5 seconds for full compilation
 * - Current: < 100ms (simplified implementation)
 */
class KtsCompiler(
    private val cacheDir: File
) {
    /**
     * Compile a Kotlin script to JVM bytecode.
     *
     * **Current behavior**: Creates a marker JAR file for testing.
     * **Future**: Will use kotlin-scripting-jvm-host for real compilation.
     *
     * @param scriptSource The .kts source code
     * @param scriptId Unique identifier for this script (used for caching)
     * @return Result containing CompiledScript or error
     */
    suspend fun compile(
        scriptSource: String,
        scriptId: String
    ): Result<CompiledScript> = withContext(Dispatchers.IO) {
        try {
            // Validate input
            if (scriptSource.isBlank()) {
                return@withContext Result.failure(
                    CompilationException("Script source is empty")
                )
            }

            // Basic syntax validation (simplified)
            if (!scriptSource.contains("class") && !scriptSource.contains("fun")) {
                return@withContext Result.failure(
                    CompilationException(
                        "Script must contain at least one class or function"
                    )
                )
            }

            // Create cache directory
            cacheDir.mkdirs()

            // Create JAR file (simplified - marker file for now)
            val jarFile = File(cacheDir, "$scriptId.jar")

            // TODO: Replace with actual kotlin-scripting compilation
            // For now, write source code to JAR as a marker
            jarFile.writeText(scriptSource)

            Result.success(CompiledScript(scriptId, jarFile))

        } catch (e: Exception) {
            Result.failure(
                CompilationException(
                    "Unexpected compilation error for $scriptId: ${e.message}",
                    e
                )
            )
        }
    }
}

/**
 * Result of successful script compilation.
 *
 * @param id The script identifier
 * @param jarFile The compiled JAR file containing JVM bytecode
 */
data class CompiledScript(
    val id: String,
    val jarFile: File
)

/**
 * Exception thrown when script compilation fails.
 */
class CompilationException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
