package com.example.whisperapp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.whisperapp.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: TranscriptionViewModel by viewModels()

    // マイク権限リクエスト
    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startRecording()
        } else {
            Toast.makeText(this, "マイク権限が必要です", Toast.LENGTH_LONG).show()
        }
    }

    // モデルファイル選択
    private val pickModelFile = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.loadModelFromUri(it) }
    }

    // 音声ファイル選択
    private val pickAudioFile = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.transcribeFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        setupClickListeners()
        observeViewModel()
    }

    private fun setupClickListeners() {
        // モデル読み込みボタン
        binding.btnLoadModel.setOnClickListener {
            pickModelFile.launch("*/*")
        }

        // 録音開始/停止ボタン
        binding.btnRecord.setOnClickListener {
            when (viewModel.uiState.value) {
                is TranscriptionViewModel.UiState.Recording -> {
                    viewModel.stopRecordingAndTranscribe()
                }
                else -> {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED) {
                        viewModel.startRecording()
                    } else {
                        requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }
        }

        // ファイル選択ボタン
        binding.btnPickFile.setOnClickListener {
            pickAudioFile.launch("audio/*")
        }

        // コピーボタン
        binding.btnCopy.setOnClickListener {
            val text = binding.tvTranscription.text.toString()
            if (text.isNotEmpty()) {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("transcription", text))
                Toast.makeText(this, "クリップボードにコピーしました", Toast.LENGTH_SHORT).show()
            }
        }

        // クリアボタン
        binding.btnClear.setOnClickListener {
            viewModel.clearTranscription()
        }

        // 共有ボタン
        binding.btnShare.setOnClickListener {
            val text = binding.tvTranscription.text.toString()
            if (text.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(intent, "共有"))
            }
        }

        // 言語選択チップ
        binding.chipGroupLanguage.setOnCheckedStateChangeListener { _, checkedIds ->
            val lang = when {
                checkedIds.contains(R.id.chipJa) -> "ja"
                checkedIds.contains(R.id.chipEn) -> "en"
                checkedIds.contains(R.id.chipZh) -> "zh"
                checkedIds.contains(R.id.chipKo) -> "ko"
                checkedIds.contains(R.id.chipAuto) -> "auto"
                else -> "ja"
            }
            viewModel.setLanguage(lang)
        }

        // 英語翻訳スイッチ
        binding.switchTranslate.setOnCheckedChangeListener { _, checked ->
            viewModel.setTranslateToEnglish(checked)
        }
    }

    private fun observeViewModel() {
        viewModel.uiState.observe(this) { state ->
            updateUiForState(state)
        }

        viewModel.transcriptionText.observe(this) { text ->
            binding.tvTranscription.text = text
            binding.tvPlaceholder.visibility = if (text.isEmpty()) View.VISIBLE else View.GONE
            binding.layoutResultActions.visibility = if (text.isNotEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.recordingDuration.observe(this) { duration ->
            if (viewModel.uiState.value is TranscriptionViewModel.UiState.Recording) {
                binding.tvStatus.text = "録音中... %.1f秒".format(duration)
            }
        }

        viewModel.modelInfo.observe(this) { info ->
            binding.tvModelInfo.text = info
        }

        viewModel.selectedLanguage.observe(this) { lang ->
            val chipId = when (lang) {
                "ja" -> R.id.chipJa
                "en" -> R.id.chipEn
                "zh" -> R.id.chipZh
                "ko" -> R.id.chipKo
                else -> R.id.chipAuto
            }
            binding.chipGroupLanguage.check(chipId)
        }
    }

    private fun updateUiForState(state: TranscriptionViewModel.UiState) {
        when (state) {
            is TranscriptionViewModel.UiState.Idle -> {
                binding.tvStatus.text = "モデルを読み込んでください"
                binding.btnRecord.isEnabled = false
                binding.btnRecord.text = "録音開始"
                binding.btnPickFile.isEnabled = false
                binding.progressBar.visibility = View.GONE
                binding.btnRecord.setIconResource(R.drawable.ic_mic)
            }

            is TranscriptionViewModel.UiState.LoadingModel -> {
                binding.tvStatus.text = "モデル読み込み中..."
                binding.btnRecord.isEnabled = false
                binding.btnPickFile.isEnabled = false
                binding.progressBar.visibility = View.VISIBLE
            }

            is TranscriptionViewModel.UiState.ModelLoaded -> {
                binding.tvStatus.text = "準備完了"
                binding.btnRecord.isEnabled = true
                binding.btnRecord.text = "録音開始"
                binding.btnRecord.setIconResource(R.drawable.ic_mic)
                binding.btnPickFile.isEnabled = true
                binding.progressBar.visibility = View.GONE
            }

            is TranscriptionViewModel.UiState.Recording -> {
                binding.tvStatus.text = "録音中..."
                binding.btnRecord.isEnabled = true
                binding.btnRecord.text = "停止して文字起こし"
                binding.btnRecord.setIconResource(R.drawable.ic_stop)
                binding.btnPickFile.isEnabled = false
                binding.progressBar.visibility = View.GONE
            }

            is TranscriptionViewModel.UiState.Transcribing -> {
                binding.tvStatus.text = state.progress.ifEmpty { "文字起こし中..." }
                binding.btnRecord.isEnabled = false
                binding.btnPickFile.isEnabled = false
                binding.progressBar.visibility = View.VISIBLE
            }

            is TranscriptionViewModel.UiState.Error -> {
                binding.tvStatus.text = "エラーが発生しました"
                binding.progressBar.visibility = View.GONE
                binding.btnRecord.isEnabled = ctxPtr != isModelReady
                binding.btnPickFile.isEnabled = ctxPtr != isModelReady

                AlertDialog.Builder(this)
                    .setTitle("エラー")
                    .setMessage(state.message)
                    .setPositiveButton("OK") { _, _ -> viewModel.dismissError() }
                    .show()
            }
        }
    }

    // ダミー: ViewModel から ctxPtr を参照するためのプロパティ (便宜上)
    private val isModelReady: Boolean
        get() = viewModel.uiState.value != TranscriptionViewModel.UiState.Idle
            && viewModel.uiState.value != TranscriptionViewModel.UiState.LoadingModel

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_info -> {
                showInfoDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showInfoDialog() {
        AlertDialog.Builder(this)
            .setTitle("whisper.cpp について")
            .setMessage(
                "このアプリは OpenAI Whisper の C++ 実装である whisper.cpp を使用して音声文字起こしを行います。\n\n" +
                "モデルファイルは以下から取得できます:\n" +
                "https://huggingface.co/ggerganov/whisper.cpp\n\n" +
                "推奨モデル:\n" +
                "• ggml-tiny.bin (75MB) - 高速\n" +
                "• ggml-base.bin (142MB) - バランス\n" +
                "• ggml-small.bin (466MB) - 高精度"
            )
            .setPositiveButton("閉じる", null)
            .show()
    }
}
