package com.tomandy.oneclaw.workspace.embedding

import android.util.Log
import java.io.File

class MemoryIndexer(
    private val store: EmbeddingStore,
    private val embeddingService: EmbeddingService,
    private val workspaceRoot: File
) {

    suspend fun syncAll() {
        val memoryFiles = collectMemoryFiles()
        val indexedPaths = store.getIndexedFilePaths()

        for (file in memoryFiles) {
            val relativePath = file.relativeTo(workspaceRoot).path
            syncFile(relativePath, file)
        }

        // Remove chunks for deleted files
        val currentPaths = memoryFiles.map { it.relativeTo(workspaceRoot).path }.toSet()
        for (stalePath in indexedPaths - currentPaths) {
            store.deleteChunksForFile(stalePath)
        }
    }

    suspend fun syncFile(relativePath: String, file: File) {
        if (!file.exists()) {
            store.deleteChunksForFile(relativePath)
            return
        }

        val text = file.readText()
        if (text.isBlank()) {
            store.deleteChunksForFile(relativePath)
            return
        }

        val chunks = ChunkingUtils.chunkText(text)
        val existingChunks = store.getChunksForFile(relativePath)
        val existingByIndex = existingChunks.associateBy { it.chunkIndex }

        // Find chunks that need (re)embedding
        val newChunks = chunks.filter { chunk ->
            val existing = existingByIndex[chunk.index]
            existing == null || existing.contentHash != chunk.contentHash
        }

        if (newChunks.isNotEmpty()) {
            try {
                val embeddings = embeddingService.embed(newChunks.map { it.content })
                for ((i, chunk) in newChunks.withIndex()) {
                    store.upsertChunk(
                        filePath = relativePath,
                        chunkIndex = chunk.index,
                        content = chunk.content,
                        contentHash = chunk.contentHash,
                        embedding = embeddings[i]
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to embed chunks for $relativePath: ${e.message}")
                throw e
            }
        }

        // Remove chunks that no longer exist in the file
        val validIndices = chunks.map { it.index }.toSet()
        store.deleteStaleChunks(relativePath, validIndices)
    }

    fun isEmpty(): Boolean = store.getStats().chunkCount == 0

    private fun collectMemoryFiles(): List<File> {
        val files = mutableListOf<File>()

        val memoryMd = File(workspaceRoot, "MEMORY.md")
        if (memoryMd.exists()) files.add(memoryMd)

        val memoryDir = File(workspaceRoot, "memory")
        if (memoryDir.isDirectory) {
            memoryDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
                ?.sortedBy { it.name }
                ?.let { files.addAll(it) }
        }

        return files
    }

    companion object {
        private const val TAG = "MemoryIndexer"
    }
}
