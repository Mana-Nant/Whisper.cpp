package com.example.whisperapp

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteOrder
import kotlin.math.min

class AudioRecorder {

    companion object {
        private const val TAG = "AudioRecorder"
        const val SAMPLE_RATE = 16000
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
            Log.e(TAG, "AudioRecord 初期化失敗")
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
        recordedSamples.clear()
        return FloatArray(samples.size) { i -> samples[i].toFloat() / 32768.0f }
    }

    fun cancelRecording() {
        isRecording = false
        try { audioRecord?.stop() } catch (e: Exception) {}
        try { audioRecord?.release() } catch (e: Exception) {}
        audioRecord = null
        recordedSamples.clear()
    }

    fun getRecordingDuration(): Float {
        return synchronized(recordedSamples) {
            recordedSamples.size.toFloat() / SAMPLE_RATE
        }
    }

    /**
     * URI から直接音声を読み込む (MP3/AAC/M4A/WAV/OGG 対応)
     * ファイルコピーなしで直接デコード
     */
    suspend fun loadAudioFromUri(context: Context, uri: Uri): FloatArray =
        withContext(Dispatchers.IO) {
            Log.i(TAG, "URI から音声読み込み: $uri")
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                decodeWithExtractor(extractor)
            } finally {
                extractor.release()
            }
        }

    suspend fun loadAudioFile(file: File): FloatArray = withContext(Dispatchers.IO) {
        Log.i(TAG, "ファイルから音声読み込み: ${file.name}")
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            decodeWithExtractor(extractor)
        } finally {
            extractor.release()
        }
    }

    private fun decodeWithExtractor(extractor: MediaExtractor): FloatArray {
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
            error("音声トラックが見つかりません")
        }

        extractor.selectTrack(audioTrackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME)!!
        val srcSampleRate = try {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } catch (e: Exception) { 44100 }
        val srcChannels = try {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } catch (e: Exception) { 1 }

        Log.i(TAG, "フォーマット: $mime, ${srcSampleRate}Hz, ${srcChannels}ch")

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val outputSamples = ArrayList<Float>()
        val timeoutUs = 10000L
        var inputDone = false
        var outputDone = false

        try {
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
                        outputSamples.add(shortBuffer.get().toFloat() / 32768.0f)
                    }
                    codec.releaseOutputBuffer(outputIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
            }
        } finally {
            try { codec.stop() } catch (e: Exception) {}
            try { codec.release() } catch (e: Exception) {}
        }

        Log.i(TAG, "デコード完了: ${outputSamples.size} サンプル (${srcChannels}ch)")

        // ステレオ → モノラル変換
        val monoSamples = if (srcChannels >= 2) {
            FloatArray(outputSamples.size / srcChannels) { i ->
                var sum = 0f
                for (ch in 0 until srcChannels) {
                    sum += outputSamples.getOrElse(i * srcChannels + ch) { 0f }
                }
                sum / srcChannels
            }
        } else {
            outputSamples.toFloatArray()
        }

        // リサンプリング
        return if (srcSampleRate == SAMPLE_RATE) {
            monoSamples
        } else {
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
