package com.tomandy.oneclaw.util

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Transcodes audio files to 16-bit PCM WAV.
 * Uses Android MediaExtractor + MediaCodec so no third-party libraries are needed.
 */
object AudioTranscoder {

    private const val TAG = "AudioTranscoder"
    private const val TIMEOUT_US = 10_000L

    /**
     * Convert [inputPath] to a WAV file. Returns the WAV file path, or null on failure.
     * If the input is already WAV, returns [inputPath] unchanged.
     */
    fun transcodeToWav(inputPath: String): String? {
        if (inputPath.endsWith(".wav", ignoreCase = true)) return inputPath

        val inputFile = File(inputPath)
        if (!inputFile.exists()) return null

        val outputFile = File(inputFile.parent, "${inputFile.nameWithoutExtension}.wav")

        return try {
            decodeToWav(inputPath, outputFile.absolutePath)
            outputFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Transcode failed for $inputPath: ${e.message}", e)
            outputFile.delete()
            null
        }
    }

    private fun decodeToWav(inputPath: String, outputPath: String) {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)

        // Find the first audio track
        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                ?.startsWith("audio/") == true
        } ?: throw IllegalArgumentException("No audio track found")

        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)
            ?: throw IllegalArgumentException("No MIME type")

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcmChunks = mutableListOf<ByteArray>()
        var totalBytes = 0
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            // Feed input
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(
                            inputIndex, 0, 0, 0,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(
                            inputIndex, 0, sampleSize,
                            extractor.sampleTime, 0
                        )
                        extractor.advance()
                    }
                }
            }

            // Drain output
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            if (outputIndex >= 0) {
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }
                val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                val chunk = ByteArray(bufferInfo.size)
                outputBuffer.position(bufferInfo.offset)
                outputBuffer.get(chunk)
                pcmChunks.add(chunk)
                totalBytes += chunk.size
                codec.releaseOutputBuffer(outputIndex, false)
            }
        }

        codec.stop()
        codec.release()

        // Read output format from codec (may differ from input format)
        val outFormat = codec.outputFormat
        val sampleRate = outFormat.getIntegerSafe(MediaFormat.KEY_SAMPLE_RATE)
            ?: format.getIntegerSafe(MediaFormat.KEY_SAMPLE_RATE)
            ?: 44100
        val channels = outFormat.getIntegerSafe(MediaFormat.KEY_CHANNEL_COUNT)
            ?: format.getIntegerSafe(MediaFormat.KEY_CHANNEL_COUNT)
            ?: 1
        val bitsPerSample = 16

        extractor.release()

        // Write WAV file
        writeWav(outputPath, pcmChunks, totalBytes, sampleRate, channels, bitsPerSample)
    }

    private fun writeWav(
        path: String,
        pcmChunks: List<ByteArray>,
        totalDataBytes: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int
    ) {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        RandomAccessFile(path, "rw").use { raf ->
            // RIFF header
            raf.writeBytes("RIFF")
            raf.writeIntLE(36 + totalDataBytes)
            raf.writeBytes("WAVE")

            // fmt chunk
            raf.writeBytes("fmt ")
            raf.writeIntLE(16) // chunk size
            raf.writeShortLE(1) // PCM format
            raf.writeShortLE(channels)
            raf.writeIntLE(sampleRate)
            raf.writeIntLE(byteRate)
            raf.writeShortLE(blockAlign)
            raf.writeShortLE(bitsPerSample)

            // data chunk
            raf.writeBytes("data")
            raf.writeIntLE(totalDataBytes)
            for (chunk in pcmChunks) {
                raf.write(chunk)
            }
        }
    }

    private fun RandomAccessFile.writeIntLE(value: Int) {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        write(buf.array())
    }

    private fun RandomAccessFile.writeShortLE(value: Int) {
        val buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort())
        write(buf.array())
    }

    private fun MediaFormat.getIntegerSafe(key: String): Int? {
        return try {
            getInteger(key)
        } catch (_: Exception) {
            null
        }
    }
}
