package com.tomandy.oneclaw.workspace

fun interface WorkspaceWriteListener {
    suspend fun onFileWritten(relativePath: String)
}
