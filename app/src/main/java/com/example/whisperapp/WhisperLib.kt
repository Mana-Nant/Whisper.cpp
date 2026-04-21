package com.example.whisperapp

/**
 * WhisperLib - whisper.cpp の JNI ラッパー
 *
 * 使用方法:
 *   val whisper = WhisperLib()
 *   val ctxPtr = whisper.init("/path/to/model.bin")
 *   val text = whisper.transcribe(ctxPtr, floatArray, "ja", false, null)
 *   whisper.free(ctxPtr)
 */
class WhisperLib {

    companion object {
        init {
            System.loadLibrary("whisper_jni")
        }
    }

    /**
     * モデルを読み込む
     * @param modelPath GGML モデルファイルのパス
     * @return コンテキストポインタ (0 の場合は失敗)
     */
    external fun init(modelPath: String): Long

    /**
     * コンテキストを解放する
     * @param ctxPtr init() で返されたポインタ
     */
    external fun free(ctxPtr: Long)

    /**
     * 音声データを文字起こしする
     * @param ctxPtr コンテキストポインタ
     * @param audioData 16kHz モノラル PCM (float32) データ
     * @param language 言語コード ("ja", "en", "auto" など)
     * @param translate true の場合、英語に翻訳
     * @param callback セグメント逐次受信用コールバック (null 可)
     * @return 文字起こし結果テキスト
     */
    external fun transcribe(
        ctxPtr: Long,
        audioData: FloatArray,
        language: String,
        translate: Boolean,
        callback: TranscribeCallback?
    ): String

    /**
     * システム情報を取得する
     */
    external fun getSystemInfo(): String

    /**
     * モデルが読み込まれているか確認
     */
    external fun isModelLoaded(ctxPtr: Long): Boolean

    /**
     * セグメント逐次受信用コールバックインターフェース
     */
    interface TranscribeCallback {
        fun onNewSegment(text: String)
    }
}
