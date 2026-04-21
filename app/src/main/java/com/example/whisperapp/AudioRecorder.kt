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

/**
 * AudioRecorder - whisper.cpp 用 16kHz モノラル PCM 録音クラス
 */
class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000       // whisper.cpp は 16kHz が必須
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val recordedSamples = mutableListOf<Short>()

    val isActive: Boolean get() = isRecording

    /**
     * 録音を開始する
     */
    @SuppressLint("MissingPermission")
    fun startRecording() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            .coerceAtLeast(4096)

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord の初期化に失敗しました")
            return
        }

        recordedSamples.clear()
        isRecording = true
        audioRecord?.startRecording()
        Log.i(TAG, "録音開始: ${SAMPLE_RATE}Hz, バッファ=${bufferSize}")

        // バックグラウンドで読み取りループ
        Thread {
            val buffer = ShortArray(bufferSize / 2)
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    synchronized(recordedSamples) {
                        for (i in 0 until read) {
                            recordedSamples.add(buffer[i])
                        }
                    }
                }
            }
        }.start()
    }

    /**
     * 録音を停止して PCM float32 データを返す
     * @return 16kHz モノラル Float32 PCM データ
     */
    fun stopRecording(): FloatArray {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        val samples: List<Short>
        synchronized(recordedSamples) {
            samples = recordedSamples.toList()
        }

        Log.i(TAG, "録音停止: ${samples.size} サンプル (${samples.size / SAMPLE_RATE} 秒)")

        // Int16 -> Float32 正規化
        return FloatArray(samples.size) { i ->
            samples[i].toFloat() / 32768.0f
        }
    }

    /**
     * 録音をキャンセルする
     */
    fun cancelRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recordedSamples.clear()
    }

    /**
     * 現在の録音時間 (秒)
     */
    fun getRecordingDuration(): Float {
        return synchronized(recordedSamples) {
            recordedSamples.size.toFloat() / SAMPLE_RATE
        }
    }

    /**
     * WAV ファイルからデータを読み込む (16kHz へリサンプリング)
     */
    suspend fun loadWavFile(file: File): FloatArray = withContext(Dispatchers.IO) {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // WAV ヘッダー解析
        buffer.position(22)
        val channels = buffer.short.toInt()
        val sampleRate = buffer.int
        buffer.position(34)
        val bitsPerSample = buffer.short.toInt()
        buffer.position(44) // データチャンク開始

        Log.i(TAG, "WAV: ${sampleRate}Hz, ${channels}ch, ${bitsPerSample}bit")

        val rawSamples = mutableListOf<Float>()

        // PCM データ読み取り
        while (buffer.remaining() >= 2) {
            if (channels == 2) {
                val left = buffer.short.toFloat() / 32768.0f
                val right = if (buffer.remaining() >= 2) buffer.short.toFloat() / 32768.0f else 0f
                rawSamples.add((left + right) / 2f) // ステレオ -> モノラル
            } else {
                rawSamples.add(buffer.short.toFloat() / 32768.0f)
            }
        }

        // リサンプリング (必要な場合)
        if (sampleRate == SAMPLE_RATE) {
            rawSamples.toFloatArray()
        } else {
            resample(rawSamples.toFloatArray(), sampleRate, SAMPLE_RATE)
        }
    }

    /**
     * 線形補間リサンプリング
     */
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
