package com.tomandy.oneclaw.audio

interface SttProvider {
    val displayName: String
    suspend fun transcribe(audioFilePath: String): Result<String>
}
