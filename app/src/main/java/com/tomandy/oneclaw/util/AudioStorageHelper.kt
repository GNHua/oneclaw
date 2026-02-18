package com.tomandy.oneclaw.util

import android.util.Base64
import java.io.File

object AudioStorageHelper {

    /**
     * Read an audio file and return it as base64-encoded WAV with MIME type.
     * Non-WAV files are transcoded to WAV first so all LLM providers receive
     * a universally supported format.
     */
    fun readAsBase64(filePath: String): Pair<String, String>? {
        val file = File(filePath)
        if (!file.exists()) return null

        val wavPath = AudioTranscoder.transcodeToWav(filePath) ?: return null
        val wavFile = File(wavPath)
        val bytes = wavFile.readBytes()
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        // Clean up transcoded file if it differs from the original
        if (wavPath != filePath) {
            wavFile.delete()
        }

        return base64 to "audio/wav"
    }
}
