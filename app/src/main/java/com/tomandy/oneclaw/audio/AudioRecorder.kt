package com.tomandy.oneclaw.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.util.UUID

class AudioRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var currentFilePath: String? = null

    fun start(conversationId: String): String {
        val dir = File(context.filesDir, "chat_audio/$conversationId")
        dir.mkdirs()
        val file = File(dir, "${UUID.randomUUID()}.m4a")
        currentFilePath = file.absolutePath

        recorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioSamplingRate(44100)
            setAudioEncodingBitRate(128000)
            setOutputFile(file.absolutePath)
            prepare()
            start()
        }

        return file.absolutePath
    }

    fun stop(): String? {
        recorder?.apply {
            stop()
            release()
        }
        recorder = null
        return currentFilePath.also { currentFilePath = null }
    }

    fun cancel() {
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
            // MediaRecorder may throw if stopped too quickly
            recorder?.release()
        }
        recorder = null
        currentFilePath?.let { File(it).delete() }
        currentFilePath = null
    }

    val isRecording: Boolean get() = recorder != null
}
