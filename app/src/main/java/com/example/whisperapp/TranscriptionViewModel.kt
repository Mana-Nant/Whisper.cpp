package com.example.whisperapp

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * アプリの状態を管理する ViewModel
 */
class TranscriptionViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TranscriptionVM"
        private const val PREFS_NAME = "whisper_prefs"
        private const val PREF_MODEL_PATH = "model_path"
        private const val PREF_LANGUAGE = "language"
    }

    // UI 状態
    sealed class UiState {
        object Idle : UiState()
        object LoadingModel : UiState()
        object ModelLoaded : UiState()
        object Recording : UiState()
        data class Transcribing(val progress: String = "") : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableLiveData<UiState>(UiState.Idle)
    val uiState: LiveData<UiState> = _uiState

    private val _transcriptionText = MutableLiveData<String>("")
    val transcriptionText: LiveData<String> = _transcriptionText

    private val _recordingDuration = MutableLiveData<Float>(0f)
    val recordingDuration: LiveData<Float> = _recordingDuration

    private val _modelInfo = MutableLiveData<String>("モデル未読み込み")
    val modelInfo: LiveData<String> = _modelInfo

    private val _selectedLanguage = MutableLiveData("ja")
    val selectedLanguage: LiveData<String> = _selectedLanguage

    private val _translateToEnglish = MutableLiveData(false)
    val translateToEnglish: LiveData<Boolean> = _translateToEnglish

    // 内部状態
    private val whisperLib = WhisperLib()
    private val audioRecorder = AudioRecorder()
    private var ctxPtr: Long = 0L
    private var transcribeJob: Job? = null
    private var durationTimerJob: Job? = null

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // 保存済み設定を復元
        _selectedLanguage.value = prefs.getString(PREF_LANGUAGE, "ja") ?: "ja"

        // 保存済みモデルパスがあれば自動読み込み
        val savedModelPath = prefs.getString(PREF_MODEL_PATH, null)
        if (savedModelPath != null && File(savedModelPath).exists()) {
            loadModel(savedModelPath)
        }
    }

    /**
     * モデルを読み込む
     */
    fun loadModel(modelPath: String) {
        viewModelScope.launch {
            _uiState.value = UiState.LoadingModel
            _modelInfo.value = "モデル読み込み中..."

            withContext(Dispatchers.IO) {
                // 既存コンテキストを解放
                if (ctxPtr != 0L) {
                    whisperLib.free(ctxPtr)
                    ctxPtr = 0L
                }

                ctxPtr = whisperLib.init(modelPath)
            }

            if (ctxPtr != 0L) {
                val sysInfo = withContext(Dispatchers.IO) {
                    whisperLib.getSystemInfo()
                }
                val fileName = File(modelPath).name
                _modelInfo.value = "✓ $fileName\n$sysInfo"
                _uiState.value = UiState.ModelLoaded

                // モデルパスを保存
                prefs.edit().putString(PREF_MODEL_PATH, modelPath).apply()
                Log.i(TAG, "モデル読み込み成功: $modelPath")
            } else {
                _modelInfo.value = "モデルの読み込みに失敗しました"
                _uiState.value = UiState.Error("モデルの読み込みに失敗しました。\nファイルが正しいか確認してください。")
            }
        }
    }

    /**
     * Uri からモデルをアプリ内にコピーして読み込む
     */
    fun loadModelFromUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = UiState.LoadingModel
            _modelInfo.value = "モデルをコピー中..."

            val destFile = File(getApplication<Application>().filesDir, "model.bin")

            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            if (destFile.exists() && destFile.length() > 0) {
                loadModel(destFile.absolutePath)
            } else {
                _uiState.value = UiState.Error("モデルファイルのコピーに失敗しました")
            }
        }
    }

    /**
     * 録音を開始する
     */
    fun startRecording() {
        if (ctxPtr == 0L) {
            _uiState.value = UiState.Error("先にモデルを読み込んでください")
            return
        }

        audioRecorder.startRecording()
        _uiState.value = UiState.Recording
        _transcriptionText.value = ""

        // 録音時間タイマー
        durationTimerJob = viewModelScope.launch {
            while (audioRecorder.isActive) {
                _recordingDuration.value = audioRecorder.getRecordingDuration()
                kotlinx.coroutines.delay(100)
            }
        }
    }

    /**
     * 録音を停止して文字起こしを実行する
     */
    fun stopRecordingAndTranscribe() {
        durationTimerJob?.cancel()

        val audioData = audioRecorder.stopRecording()
        _recordingDuration.value = audioData.size / 16000f

        if (audioData.isEmpty()) {
            _uiState.value = UiState.ModelLoaded
            return
        }

        runTranscription(audioData)
    }

    /**
     * WAV ファイルを文字起こしする
     */
    fun transcribeFile(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = UiState.Transcribing("ファイルを読み込み中...")

            val audioData = try {
                withContext(Dispatchers.IO) {
                    val tempFile = File(getApplication<Application>().cacheDir, "temp_audio.wav")
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                    }
                    audioRecorder.loadWavFile(tempFile)
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error("ファイルの読み込みに失敗しました: ${e.message}")
                return@launch
            }

            runTranscription(audioData)
        }
    }

    /**
     * 文字起こしを実行する
     */
    private fun runTranscription(audioData: FloatArray) {
        transcribeJob?.cancel()
        transcribeJob = viewModelScope.launch {
            _uiState.value = UiState.Transcribing("文字起こし中...")
            _transcriptionText.value = ""

            val language = _selectedLanguage.value ?: "ja"
            val translate = _translateToEnglish.value ?: false

            Log.i(TAG, "文字起こし開始: ${audioData.size} サンプル, lang=$language")

            val result = withContext(Dispatchers.Default) {
                whisperLib.transcribe(
                    ctxPtr,
                    audioData,
                    language,
                    translate,
                    object : WhisperLib.TranscribeCallback {
                        override fun onNewSegment(text: String) {
                            viewModelScope.launch(Dispatchers.Main) {
                                val current = _transcriptionText.value ?: ""
                                _transcriptionText.value = current + text
                            }
                        }
                    }
                )
            }

            _transcriptionText.value = result
            _uiState.value = UiState.ModelLoaded
            Log.i(TAG, "文字起こし完了: ${result.length} 文字")
        }
    }

    fun setLanguage(lang: String) {
        _selectedLanguage.value = lang
        prefs.edit().putString(PREF_LANGUAGE, lang).apply()
    }

    fun setTranslateToEnglish(enabled: Boolean) {
        _translateToEnglish.value = enabled
    }

    fun clearTranscription() {
        _transcriptionText.value = ""
    }

    fun dismissError() {
        _uiState.value = if (ctxPtr != 0L) UiState.ModelLoaded else UiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        durationTimerJob?.cancel()
        transcribeJob?.cancel()
        if (ctxPtr != 0L) {
            whisperLib.free(ctxPtr)
        }
        audioRecorder.cancelRecording()
    }
}
