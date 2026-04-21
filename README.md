# WhisperApp - Android 音声文字起こしアプリ

whisper.cpp を使用した Android 向けリアルタイム音声文字起こしアプリです。
オフラインで動作し、日本語・英語・中国語・韓国語など多言語に対応しています。

---

## 機能

- 🎤 **マイク録音** → 文字起こし
- 📁 **WAV ファイル** の文字起こし
- 🌐 **多言語対応**: 日本語・英語・中文・한국어・自動検出
- 🔄 **英語翻訳**: 任意の言語 → 英語変換
- 📋 コピー・共有機能
- 📱 完全オフライン動作

---

## セットアップ手順

### 1. リポジトリのクローン

```bash
git clone https://github.com/your-repo/WhisperApp
cd WhisperApp
```

### 2. whisper.cpp のソースを取得

```bash
cd app/src/main/cpp
git clone https://github.com/ggerganov/whisper.cpp
cd whisper.cpp
git checkout v1.7.4   # 安定版を推奨
```

### 3. GGML モデルのダウンロード

以下のいずれかのモデルをダウンロードしてください:

| モデル | サイズ | 精度 | 速度 |
|--------|--------|------|------|
| ggml-tiny.bin | 75 MB | ★★☆☆☆ | 最速 |
| ggml-tiny.en.bin | 75 MB | ★★★☆☆ | 最速 (英語専用) |
| ggml-base.bin | 142 MB | ★★★☆☆ | 速い |
| ggml-base.en.bin | 142 MB | ★★★★☆ | 速い (英語専用) |
| ggml-small.bin | 466 MB | ★★★★☆ | 普通 |
| ggml-medium.bin | 1.5 GB | ★★★★★ | 遅い |

**ダウンロード方法:**

```bash
# whisper.cpp の models スクリプトを使う場合
cd app/src/main/cpp/whisper.cpp
bash models/download-ggml-model.sh tiny
bash models/download-ggml-model.sh base

# または直接ダウンロード
# https://huggingface.co/ggerganov/whisper.cpp/tree/main
```

**Android デバイスへの転送:**
- Android Studio の Device File Explorer で `/sdcard/Download/` などに配置
- または `adb push ggml-base.bin /sdcard/Download/ggml-base.bin`
- アプリ内の「モデルを選択」ボタンでファイルを選択

### 4. Android Studio でビルド

1. Android Studio でプロジェクトを開く
2. NDK と CMake がインストールされていることを確認:
   - `SDK Manager` → `SDK Tools` → `NDK (Side by side)` ✓
   - `SDK Manager` → `SDK Tools` → `CMake` ✓
3. `Run` ボタンでビルド・インストール

---

## プロジェクト構成

```
WhisperApp/
├── app/
│   ├── build.gradle                      # ビルド設定 (NDK/CMake)
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── cpp/
│       │   ├── CMakeLists.txt            # C++ ビルド設定
│       │   ├── jni.cpp                   # JNI ブリッジ (whisper.cpp ↔ Kotlin)
│       │   └── whisper.cpp/              # ← ここにリポジトリをクローン
│       ├── java/com/example/whisperapp/
│       │   ├── MainActivity.kt           # UI Activity
│       │   ├── TranscriptionViewModel.kt # 状態管理
│       │   ├── WhisperLib.kt             # JNI ラッパー
│       │   └── AudioRecorder.kt          # 録音・リサンプリング
│       └── res/
│           ├── layout/activity_main.xml  # UI レイアウト
│           └── ...
└── README.md
```

---

## アーキテクチャ

```
┌─────────────────────────────────────────┐
│            MainActivity (Kotlin)         │
│  ViewBinding + LiveData オブザーバー     │
└──────────────────┬──────────────────────┘
                   │ observes
┌──────────────────▼──────────────────────┐
│       TranscriptionViewModel (Kotlin)    │
│  LiveData<UiState>, LiveData<String>     │
└──────┬─────────────────┬────────────────┘
       │                 │
┌──────▼──────┐   ┌──────▼──────────────┐
│ AudioRecorder│   │     WhisperLib       │
│ 16kHz PCM    │   │  JNI ラッパー        │
│ 録音・変換   │   └──────┬──────────────┘
└─────────────┘          │ JNI
                  ┌───────▼───────────────┐
                  │      jni.cpp (C++)     │
                  │  whisper.cpp API 呼出  │
                  └───────┬───────────────┘
                          │
                  ┌───────▼───────────────┐
                  │    whisper.cpp (C++)   │
                  │  GGML モデル推論       │
                  └───────────────────────┘
```

---

## よくある問題

### ビルドエラー: "whisper.h が見つかりません"
```
app/src/main/cpp/ 内に whisper.cpp リポジトリをクローンしてください:
cd app/src/main/cpp && git clone https://github.com/ggerganov/whisper.cpp
```

### モデルの読み込みに失敗する
- ファイルが GGML フォーマット (ggml-*.bin) であることを確認
- ストレージ読み取り権限を確認
- モデルが破損していないか確認 (md5sum でチェック)

### 文字起こしが遅い
- より小さいモデル (tiny/base) を使用する
- arm64-v8a デバイスで NEON 最適化が有効になっているか確認

### クラッシュ: SIGILL / SIGSEGV
- NDK バージョンが r23 以上であることを確認
- `abiFilters` が対象デバイスのアーキテクチャと一致しているか確認

---

## 必要環境

- Android Studio Hedgehog (2023.1.1) 以上
- NDK r23 以上
- CMake 3.22 以上
- Android API 26 (Android 8.0) 以上
- RAM: 推奨 2GB 以上 (モデルサイズによる)

---

## ライセンス

- このアプリ: MIT License
- whisper.cpp: MIT License (Copyright © 2022 Georgi Gerganov)
- OpenAI Whisper: MIT License
