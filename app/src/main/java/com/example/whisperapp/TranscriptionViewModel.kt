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

class TranscriptionViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "TranscriptionVM"
        private const val PREFS_NAME = "whisper_prefs"
        private const val PREF_MODEL_PATH = "model_path"
        private const val PREF_LANGUAGE = "language"
    }

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

    private val whisperLib = WhisperLib()
    private val audioRecorder = AudioRecorder()
    var ctxPtr: Long = 0L
        private set
    private var transcribeJob: Job? = null
    private var durationTimerJob: Job? = null

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        _selectedLanguage.value = prefs.getString(PREF_LANGUAGE, "ja") ?: "ja"
        val savedModelPath = prefs.getString(PREF_MODEL_PATH, null)
        if (savedModelPath != null && File(savedModelPath).exists()) {
            loadModel(savedModelPath)
        }
    }

    fun loadModel(modelPath: String) {
        viewModelScope.launch {
            _uiState.value = UiState.LoadingModel
            _modelInfo.value = "モデル読み込み中..."

            audioRecorder.cancelRecording()
            transcribeJob?.cancel()

            withContext(Dispatchers.IO) {
                val oldPtr = ctxPtr
                ctxPtr = 0L
                if (oldPtr != 0L) {
                    try { whisperLib.free(oldPtr) } catch (e: Exception) {
                        Log.e(TAG, "free失敗: ${e.message}")
                    }
                }
                Thread.sleep(300)
                ctxPtr = try {
                    whisperLib.init(modelPath)
                } catch (e: Exception) {
                    Log.e(TAG, "init失敗: ${e.message}", e)
                    0L
                }
            }

            if (ctxPtr != 0L) {
                val sysInfo = withContext(Dispatchers.IO) {
                    try { whisperLib.getSystemInfo() } catch (e: Exception) { "" }
                }
                val fileName = File(modelPath).name
                _modelInfo.value = "✓ $fileName\n$sysInfo"
                _uiState.value = UiState.ModelLoaded
                prefs.edit().putString(PREF_MODEL_PATH, modelPath).apply()
            } else {
                _modelInfo.value = "モデルの読み込みに失敗しました"
                _uiState.value = UiState.Error("モデルの読み込みに失敗しました。\nファイルが正しいか確認してください。")
            }
        }
    }

    fun loadModelFromUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.value = UiState.LoadingModel
            _modelInfo.value = "モデルをコピー中..."

            val destFile = File(getApplication<Application>().filesDir, "model.bin")

            val success = withContext(Dispatchers.IO) {
                try {
                    val inputStream = getApplication<Application>()
                        .contentResolver.openInputStream(uri)
                        ?: return@withContext false
                    inputStream.use { input ->
                        FileOutputStream(destFile).use { output ->
                            val buffer = ByteArray(4 * 1024 * 1024)
                            var bytes = input.read(buffer)
                            while (bytes >= 0) {
                                output.write(buffer, 0, bytes)
                                bytes = input.read(buffer)
                            }
                            output.flush()
                        }
                    }
                    destFile.exists() && destFile.length() > 0
                } catch (e: Exception) {
                    Log.e(TAG, "モデルコピー失敗: ${e.message}", e)
                    false
                }
            }

            if (success) {
                loadModel(destFile.absolutePath)
            } else {
                _uiState.value = UiState.Error(
                    "モデルファイルのコピーに失敗しました。\nストレージ空き容量を確認してください。"
                )
            }
        }
    }

    fun startRecording() {
        if (ctxPtr == 0L) {
            _uiState.value = UiState.Error("先にモデルを読み込んでください")
            return
        }
        audioRecorder.startRecording()
        _uiState.value = UiState.Recording
        _transcriptionText.value = ""

        durationTimerJob = viewModelScope.launch {
            while (audioRecorder.isActive) {
                _recordingDuration.value = audioRecorder.getRecordingDuration()
                kotlinx.coroutines.delay(100)
            }
        }
    }

    fun stopRecordingAndTranscribe() {
        durationTimerJob?.cancel()
        val audioData = audioRecorder.stopRecording()
        _recordingDuration.value = audioData.size / 16000f
        if (audioData.isEmpty()) {
            _uiState.value = UiState.ModelLoaded
            return
        }
        transcribeJob?.cancel()
        transcribeJob = viewModelScope.launch {
            runTranscriptionInternal(audioData)
        }
    }

    fun transcribeFile(uri: Uri) {
        transcribeJob?.cancel()
        transcribeJob = viewModelScope.launch {
            _uiState.value = UiState.Transcribing("ファイルを読み込み中...")
            _transcriptionText.value = ""

            val audioData = try {
                withContext(Dispatchers.IO) {
                    audioRecorder.loadAudioFromUri(getApplication<Application>(), uri)
                }
            } catch (e: Exception) {
                Log.e(TAG, "音声読み込み失敗: ${e.message}", e)
                _uiState.value = UiState.Error(
                    "ファイルの読み込みに失敗しました。\n" +
                    "対応形式: MP3, AAC, M4A, WAV\n" +
                    "エラー: ${e.message}"
                )
                return@launch
            }

            if (audioData.isEmpty()) {
                _uiState.value = UiState.Error("音声データが空です")
                return@launch
            }

            Log.i(TAG, "音声読み込み完了: ${audioData.size} サンプル " +
                "(${audioData.size / AudioRecorder.SAMPLE_RATE} 秒)")

            runTranscriptionInternal(audioData)
        }
    }

    private suspend fun runTranscriptionInternal(audioData: FloatArray) {
        _uiState.value = UiState.Transcribing("文字起こし中...")

        val language = _selectedLanguage.value ?: "ja"
        val translate = _translateToEnglish.value ?: false

        val result = withContext(Dispatchers.Default) {
            try {
                whisperLib.transcribe(
                    ctxPtr, audioData, language, translate,
                    object : WhisperLib.TranscribeCallback {
                        override fun onNewSegment(text: String) {
                            viewModelScope.launch(Dispatchers.Main) {
                                val current = _transcriptionText.value ?: ""
                                _transcriptionText.value = current + text
                            }
                        }
                    }
                )
            } catch (e: Exception) {
                Log.e(TAG, "文字起こし失敗: ${e.message}", e)
                "[エラー: ${e.message}]"
            }
        }

        _transcriptionText.value = result
        _uiState.value = UiState.ModelLoaded
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
            try { whisperLib.free(ctxPtr) } catch (e: Exception) {}
        }
        audioRecorder.cancelRecording()
    }
}
