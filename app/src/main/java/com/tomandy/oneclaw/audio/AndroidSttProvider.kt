package com.tomandy.oneclaw.audio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * STT provider using Android's built-in SpeechRecognizer.
 * Performs live recognition from the microphone.
 */
class AndroidSttProvider(private val context: Context) : SttProvider {

    override val displayName = "Android (built-in)"

    override suspend fun transcribe(audioFilePath: String): Result<String> {
        return Result.failure(
            UnsupportedOperationException("Android SpeechRecognizer uses live mic input. Use recognizeLive() instead.")
        )
    }

    /**
     * Start live speech recognition. Returns the transcribed text when the user stops speaking.
     * Must be called when RECORD_AUDIO permission is granted.
     */
    suspend fun recognizeLive(): Result<String> = suspendCancellableCoroutine { cont ->
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    recognizer.destroy()
                    if (text != null) {
                        cont.resume(Result.success(text))
                    } else {
                        cont.resume(Result.failure(Exception("No recognition results")))
                    }
                }

                override fun onError(error: Int) {
                    recognizer.destroy()
                    val message = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission not granted"
                        SpeechRecognizer.ERROR_NETWORK -> "Network error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech detected"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input"
                        else -> "Speech recognition error ($error)"
                    }
                    cont.resume(Result.failure(Exception(message)))
                }

                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            recognizer.startListening(intent)

            cont.invokeOnCancellation {
                recognizer.cancel()
                recognizer.destroy()
            }
        }
    }
}
