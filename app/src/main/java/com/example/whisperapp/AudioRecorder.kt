package com.example.whisperapp

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val recordedSamples = mutableListOf<Short>()

    val isActive: Boolean get() = isRecording

    @SuppressLint("MissingPermission")
    fun startRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(4096)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord の初期化に失敗しました")
            return
        }

        recordedSamples.clear()
        isRecording = true
        audioRecord?.startRecording()

        Thread {
            val buffer = ShortArray(bufferSize / 2)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    synchronized(recordedSamples) {
                        for (i in 0 until read) recordedSamples.add(buffer[i])
                    }
                }
            }
        }.start()
    }

    fun stopRecording(): FloatArray {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val samples: List<Short>
        synchronized(recordedSamples) { samples = recordedSamples.toList() }

        return FloatArray(samples.size) { i -> samples[i].toFloat() / 32768.0f }
    }

    fun cancelRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordedSamples.clear()
    }

    fun getRecordingDuration(): Float {
        return synchronized(recordedSamples) {
            recordedSamples.size.toFloat() / SAMPLE_RATE
        }
    }

    suspend fun loadWavFile(file: File): FloatArray = withContext(Dispatchers.IO) {
        try {
            val bytes = file.readBytes()
            if (bytes.size < 44) error("WAVファイルが小さすぎます: ${bytes.size} bytes")

            // RIFF チェック
            val riff = String(bytes.sliceArray(0..3))
            if (riff != "RIFF") error("RIFFヘッダーがありません: $riff")

            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            buffer.position(22)
            val channels = buffer.short.toInt().coerceIn(1, 2)
            val sampleRate = buffer.int.let { if (it <= 0) 16000 else it }
            buffer.position(34)
            val bitsPerSample = buffer.short.toInt().let { if (it <= 0) 16 else it }

            // data チャンクを探す
            var dataOffset = 44
            buffer.position(36)
            while (buffer.remaining() >= 8) {
                val chunkIdBytes = ByteArray(4)
                buffer.get(chunkIdBytes)
                val chunkId = String(chunkIdBytes)
                val chunkSize = buffer.int
                if (chunkId == "data") {
                    dataOffset = buffer.position()
                    break
                }
                val skip = minOf(chunkSize, buffer.remaining())
                buffer.position(buffer.position() + skip)
            }

            Log.i(TAG, "WAV: ${sampleRate}Hz, ${channels}ch, ${bitsPerSample}bit, dataOffset=$dataOffset")

            buffer.position(dataOffset)
            val bytesPerSample = maxOf(bitsPerSample / 8, 1)
            val rawSamples = mutableListOf<Float>()

            while (buffer.remaining() >= bytesPerSample * channels) {
                val left = when (bitsPerSample) {
                    32 -> buffer.int.toFloat() / 2147483648.0f
                    else -> buffer.short.toFloat() / 32768.0f
                }
                val right = if (channels == 2) {
                    when (bitsPerSample) {
                        32 -> buffer.int.toFloat() / 2147483648.0f
                        else -> buffer.short.toFloat() / 32768.0f
                    }
                } else left
                rawSamples.add((left + right) / if (channels == 2) 2f else 1f)
            }

            Log.i(TAG, "サンプル数: ${rawSamples.size}")

            if (sampleRate == SAMPLE_RATE) rawSamples.toFloatArray()
            else resample(rawSamples.toFloatArray(), sampleRate, SAMPLE_RATE)

        } catch (e: Exception) {
            Log.e(TAG, "WAV読み込みエラー: ${e.message}", e)
            throw e
        }
    }

    private fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        val ratio = fromRate.toDouble() / toRate
        val outputLength = (input.size / ratio).toInt()
        return FloatArray(outputLength) { i ->
            val srcPos = i * ratio
            val idx = srcPos.toInt()
            val frac = (srcPos - idx).toFloat()
            val a = input.getOrElse(idx) { 0f }
            val b = input.getOrElse(min(idx + 1, input.size - 1)) { 0f }
            a + frac * (b - a)
        }
    }
}
