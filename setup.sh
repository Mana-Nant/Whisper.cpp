#!/bin/bash
# =============================================================
# WhisperApp セットアップスクリプト
# whisper.cpp のクローンとモデルのダウンロードを自動化します
# =============================================================

set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CPP_DIR="$SCRIPT_DIR/app/src/main/cpp"

echo "========================================"
echo "  WhisperApp セットアップ"
echo "========================================"
echo ""

# 1. whisper.cpp のクローン
WHISPER_DIR="$CPP_DIR/whisper.cpp"
if [ -d "$WHISPER_DIR" ]; then
    echo "[OK] whisper.cpp は既に存在します: $WHISPER_DIR"
    cd "$WHISPER_DIR"
    echo "     最新版に更新しますか? (y/N): "
    read -r UPDATE
    if [ "$UPDATE" = "y" ] || [ "$UPDATE" = "Y" ]; then
        git pull
        echo "[OK] whisper.cpp を更新しました"
    fi
else
    echo "[1/3] whisper.cpp をクローン中..."
    cd "$CPP_DIR"
    git clone https://github.com/ggerganov/whisper.cpp
    # 安定版タグをチェックアウト
    cd whisper.cpp
    LATEST_TAG=$(git describe --tags $(git rev-list --tags --max-count=1))
    echo "     最新タグ: $LATEST_TAG"
    git checkout "$LATEST_TAG"
    echo "[OK] whisper.cpp のクローン完了"
fi

echo ""

# 2. モデルのダウンロード
MODELS_DIR="$WHISPER_DIR/models"
echo "[2/3] モデルを選択してください:"
echo "  1) tiny    (75MB)  - 最速・精度低"
echo "  2) base    (142MB) - 速い・精度普通  ← 推奨"
echo "  3) small   (466MB) - 普通・精度高"
echo "  4) medium  (1.5GB) - 遅い・精度最高"
echo "  5) スキップ"
echo ""
printf "選択 [1-5]: "
read -r MODEL_CHOICE

case "$MODEL_CHOICE" in
    1) MODEL_NAME="tiny" ;;
    2) MODEL_NAME="base" ;;
    3) MODEL_NAME="small" ;;
    4) MODEL_NAME="medium" ;;
    5)
        echo "[SKIP] モデルのダウンロードをスキップしました"
        MODEL_NAME=""
        ;;
    *)
        echo "[SKIP] 無効な選択。スキップします。"
        MODEL_NAME=""
        ;;
esac

if [ -n "$MODEL_NAME" ]; then
    MODEL_FILE="$MODELS_DIR/ggml-${MODEL_NAME}.bin"
    if [ -f "$MODEL_FILE" ]; then
        echo "[OK] モデルは既にダウンロード済みです: $MODEL_FILE"
    else
        echo "[2/3] モデルをダウンロード中: ggml-${MODEL_NAME}.bin"
        cd "$WHISPER_DIR"
        bash models/download-ggml-model.sh "$MODEL_NAME"
        echo "[OK] モデルのダウンロード完了: $MODEL_FILE"
    fi
    echo ""
    echo "  → Android デバイスへの転送:"
    echo "    adb push \"$MODEL_FILE\" /sdcard/Download/ggml-${MODEL_NAME}.bin"
fi

echo ""

# 3. NDK チェック
echo "[3/3] Android NDK のチェック..."
if [ -n "$ANDROID_NDK_HOME" ]; then
    echo "[OK] ANDROID_NDK_HOME: $ANDROID_NDK_HOME"
elif [ -n "$ANDROID_HOME" ] && [ -d "$ANDROID_HOME/ndk" ]; then
    NDK_VERSION=$(ls "$ANDROID_HOME/ndk" | tail -1)
    echo "[OK] NDK が見つかりました: $ANDROID_HOME/ndk/$NDK_VERSION"
else
    echo "[WARNING] NDK が見つかりません。"
    echo "  Android Studio → SDK Manager → SDK Tools → NDK (Side by side) をインストールしてください"
fi

echo ""
echo "========================================"
echo "  セットアップ完了!"
echo "========================================"
echo ""
echo "次のステップ:"
echo "  1. Android Studio でこのプロジェクトを開く"
echo "  2. デバイス/エミュレータに接続してビルド実行"
echo "  3. アプリ内で「モデルを選択」→ ggml-*.bin を選択"
echo ""
