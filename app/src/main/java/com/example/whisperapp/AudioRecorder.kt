package com.example.whisperapp

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
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

    /**
     * MP3 / AAC / M4A / WAV など任意の音声ファイルを
     * 16kHz モノラル Float32 PCM に変換する
     */
    suspend fun loadAudioFile(file: File): FloatArray = withContext(Dispatchers.IO) {
        Log.i(TAG, "音声ファイル読み込み: ${file.name} (${file.length()} bytes)")

        val extractor = MediaExtractor()
        extractor.setDataSource(file.absolutePath)

        // 音声トラックを探す
        var audioTrackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                format = trackFormat
                break
            }
        }

        if (audioTrackIndex < 0 || format == null) {
            extractor.release()
            error("音声トラックが見つかりません")
        }

        extractor.selectTrack(audioTrackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val srcSampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val srcChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

        Log.i(TAG, "フォーマット: $mime, ${srcSampleRate}Hz, ${srcChannels}ch")

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcmSamples = mutableListOf<Short>()
        val timeoutUs = 10000L
        var inputDone = false
        var outputDone = false

        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(timeoutUs)
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

            val bufferInfo = MediaCodec.BufferInfo()
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, timeoutUs)
            if (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)!!
                outputBuffer.order(ByteOrder.LITTLE_ENDIAN)
                val shortBuffer = outputBuffer.asShortBuffer()
                while (shortBuffer.hasRemaining()) {
                    pcmSamples.add(shortBuffer.get())
                }
                codec.releaseOutputBuffer(outputIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    outputDone = true
                }
            }
        }

        codec.stop()
        codec.release()
        extractor.release()

        Log.i(TAG, "デコード完了: ${pcmSamples.size} サンプル")

        // ステレオ → モノラル変換
        val monoSamples = if (srcChannels == 2) {
            FloatArray(pcmSamples.size / 2) { i ->
                val left = pcmSamples.getOrElse(i * 2) { 0 }.toFloat() / 32768.0f
                val right = pcmSamples.getOrElse(i * 2 + 1) { 0 }.toFloat() / 32768.0f
                (left + right) / 2f
            }
        } else {
            FloatArray(pcmSamples.size) { i -> pcmSamples[i].toFloat() / 32768.0f }
        }

        // リサンプリング
        if (srcSampleRate == SAMPLE_RATE) monoSamples
        else {
            Log.i(TAG, "リサンプリング: ${srcSampleRate}Hz → ${SAMPLE_RATE}Hz")
            resample(monoSamples, srcSampleRate, SAMPLE_RATE)
        }
    }

    // 後方互換
    suspend fun loadWavFile(file: File): FloatArray = loadAudioFile(file)

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
