package com.tomandy.oneclaw.plugin.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HeadlessCameraCapture(private val context: Context) {

    companion object {
        private const val TAG = "HeadlessCameraCapture"
    }

    suspend fun capture(outputFile: File, useFrontCamera: Boolean = false): File {
        val cameraProvider = getCameraProvider()
        val lifecycleOwner = HeadlessLifecycleOwner()

        try {
            lifecycleOwner.start()

            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = if (useFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageCapture)

            // Small delay to let the camera warm up and auto-expose
            kotlinx.coroutines.delay(500)

            return takePicture(imageCapture, outputFile)
        } finally {
            cameraProvider.unbindAll()
            lifecycleOwner.destroy()
        }
    }

    fun getAvailableCameras(): List<CameraInfo> {
        val result = mutableListOf<CameraInfo>()
        try {
            val provider = ProcessCameraProvider.getInstance(context).get()
            if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                result.add(CameraInfo("back", "Rear-facing camera"))
            }
            if (provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)) {
                result.add(CameraInfo("front", "Front-facing camera"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enumerate cameras", e)
        }
        return result
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider {
        return suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    cont.resume(future.get())
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                }
            }, Executors.newSingleThreadExecutor())
        }
    }

    private suspend fun takePicture(imageCapture: ImageCapture, outputFile: File): File {
        return suspendCancellableCoroutine { cont ->
            val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()
            imageCapture.takePicture(
                outputOptions,
                Executors.newSingleThreadExecutor(),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                        cont.resume(outputFile)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        cont.resumeWithException(exception)
                    }
                }
            )
        }
    }

    data class CameraInfo(val facing: String, val description: String)
}

private class HeadlessLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle get() = registry

    fun start() {
        registry.currentState = Lifecycle.State.STARTED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}
