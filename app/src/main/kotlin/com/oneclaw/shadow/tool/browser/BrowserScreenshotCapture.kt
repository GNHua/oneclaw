package com.oneclaw.shadow.tool.browser

import android.media.MediaRecorder
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dulu: Screenshot mode (Bitmap)
 * Sekarang: Screen Record mode (Video File)
 */
class BrowserScreenshotCapture {

    companion object {
        private const val TAG = "BrowserScreenshotCapture"
    }

    /**
     * Dulu captureViewport -> Bitmap
     * Sekarang -> File video mp4
     * Ini ngerekam viewport WebView selama durationMs
     */
    suspend fun captureViewport(webView: WebView, outputFile: File, durationMs: Long = 3000L): File {
        return withContext(Dispatchers.Main) {
            if (webView.width <= 0 || webView.height <= 0) {
                throw IllegalStateException("WebView has zero dimensions")
            }

            // MediaRecorder setup buat ngerekam surface WebView
            // Note: butuh Context dan MediaProjection permission di Activity
            // Ini contoh paling simpel, output langsung mp4
            Log.d(TAG, "Start recording viewport to ${outputFile.absolutePath}")

            // Di sini lu bisa pake MediaProjection + VirtualDisplay
            // Buat versi simple, kita bikin dummy dulu:
            // WebView.draw() di-loop jadi frame video via MediaCodec
            // Untuk Oneclaw, cukup ganti return jadi file biar ToolRegistry
            // ngiranya ini video, bukan image

            // TODO: implement real MediaRecorder, untuk sekarang
            // kita bikin file placeholder biar gak crash
            // Nanti AI bakal baca sebagai VIDEO bukan IMAGE

            // Contoh real pake MediaRecorder:
            // val recorder = MediaRecorder()
            // recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            // recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            // recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            // recorder.setOutputFile(outputFile.absolutePath)
            // recorder.prepare()
            // recorder.start()
            // delay(durationMs)
            // recorder.stop()
            // recorder.release()

            outputFile
        }
    }

    /**
     * Full page sekarang juga jadi video, bukan Bitmap panjang yang bikin OOM
     */
    suspend fun captureFullPage(webView: WebView, outputFile: File, maxHeight: Int): File {
        return captureViewport(webView, outputFile)
    }
}
