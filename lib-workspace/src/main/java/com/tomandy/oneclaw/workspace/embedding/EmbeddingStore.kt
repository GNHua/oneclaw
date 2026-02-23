package com.tomandy.oneclaw.workspace.embedding

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ChunkRecord(
    val id: String,
    val filePath: String,
    val chunkIndex: Int,
    val content: String,
    val contentHash: String,
    val embedding: FloatArray,
    val updatedAt: Long
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ChunkRecord) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}

data class StoreStats(
    val chunkCount: Int,
    val fileCount: Int
)

class EmbeddingStore(context: Context, dbDir: File) : SQLiteOpenHelper(
    context,
    File(dbDir.also { it.mkdirs() }, "vectors.db").absolutePath,
    null,
    DB_VERSION
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS chunks (
                id TEXT PRIMARY KEY,
                file_path TEXT NOT NULL,
                chunk_index INTEGER NOT NULL,
                content TEXT NOT NULL,
                content_hash TEXT NOT NULL,
                embedding BLOB,
                updated_at INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_chunks_file ON chunks(file_path)")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS meta (
                key TEXT PRIMARY KEY,
                value TEXT
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS chunks")
        db.execSQL("DROP TABLE IF EXISTS meta")
        onCreate(db)
    }

    fun getChunksForFile(filePath: String): List<ChunkRecord> {
        val results = mutableListOf<ChunkRecord>()
        readableDatabase.query(
            "chunks", null,
            "file_path = ?", arrayOf(filePath),
            null, null, "chunk_index ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(cursorToRecord(cursor))
            }
        }
        return results
    }

    fun upsertChunk(
        filePath: String,
        chunkIndex: Int,
        content: String,
        contentHash: String,
        embedding: FloatArray
    ) {
        val id = "$filePath#$chunkIndex"
        val values = ContentValues().apply {
            put("id", id)
            put("file_path", filePath)
            put("chunk_index", chunkIndex)
            put("content", content)
            put("content_hash", contentHash)
            put("embedding", floatArrayToBlob(embedding))
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "chunks", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun deleteChunksForFile(filePath: String) {
        writableDatabase.delete("chunks", "file_path = ?", arrayOf(filePath))
    }

    fun deleteStaleChunks(filePath: String, validIndices: Set<Int>) {
        if (validIndices.isEmpty()) {
            deleteChunksForFile(filePath)
            return
        }
        val placeholders = validIndices.joinToString(",") { "?" }
        val args = arrayOf(filePath) + validIndices.map { it.toString() }.toTypedArray()
        writableDatabase.delete(
            "chunks",
            "file_path = ? AND chunk_index NOT IN ($placeholders)",
            args
        )
    }

    fun getAllEmbeddings(): List<ChunkRecord> {
        val results = mutableListOf<ChunkRecord>()
        readableDatabase.query(
            "chunks", null,
            "embedding IS NOT NULL", null,
            null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(cursorToRecord(cursor))
            }
        }
        return results
    }

    fun getIndexedFilePaths(): Set<String> {
        val paths = mutableSetOf<String>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT file_path FROM chunks", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                paths.add(cursor.getString(0))
            }
        }
        return paths
    }

    fun getStats(): StoreStats {
        val chunkCount = readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM chunks", null
        ).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        val fileCount = readableDatabase.rawQuery(
            "SELECT COUNT(DISTINCT file_path) FROM chunks", null
        ).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        return StoreStats(chunkCount, fileCount)
    }

    fun getMeta(key: String): String? {
        return readableDatabase.query(
            "meta", arrayOf("value"),
            "key = ?", arrayOf(key),
            null, null, null
        ).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    fun setMeta(key: String, value: String) {
        val values = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        writableDatabase.insertWithOnConflict(
            "meta", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    private fun cursorToRecord(cursor: android.database.Cursor): ChunkRecord {
        val embeddingBlob = cursor.getBlob(cursor.getColumnIndexOrThrow("embedding"))
        return ChunkRecord(
            id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
            filePath = cursor.getString(cursor.getColumnIndexOrThrow("file_path")),
            chunkIndex = cursor.getInt(cursor.getColumnIndexOrThrow("chunk_index")),
            content = cursor.getString(cursor.getColumnIndexOrThrow("content")),
            contentHash = cursor.getString(cursor.getColumnIndexOrThrow("content_hash")),
            embedding = if (embeddingBlob != null) blobToFloatArray(embeddingBlob) else FloatArray(0),
            updatedAt = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at"))
        )
    }

    companion object {
        private const val DB_VERSION = 1

        fun floatArrayToBlob(array: FloatArray): ByteArray {
            val buffer = ByteBuffer.allocate(array.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            buffer.asFloatBuffer().put(array)
            return buffer.array()
        }

        fun blobToFloatArray(blob: ByteArray): FloatArray {
            val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            val floats = FloatArray(blob.size / 4)
            buffer.asFloatBuffer().get(floats)
            return floats
        }
    }
}
