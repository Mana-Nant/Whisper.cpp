#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <thread>
#include <atomic>
#include <functional>

#include "whisper.cpp/include/whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// グローバルコンテキスト管理
static whisper_context* g_ctx = nullptr;
static std::atomic<bool> g_is_running{false};

// ============================================================
// コールバック用 Java 参照
// ============================================================
static JavaVM* g_jvm = nullptr;
static jobject g_callback_obj = nullptr;
static jmethodID g_on_new_segment_method = nullptr;

// JNI_OnLoad: JavaVM を保存
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    g_jvm = vm;
    LOGI("JNI_OnLoad: JavaVM saved");
    return JNI_VERSION_1_6;
}

// ============================================================
// whisper_jni_init: モデルを読み込んでコンテキストを作成
// ============================================================
extern "C"
JNIEXPORT jlong JNICALL
Java_com_example_whisperapp_WhisperLib_init(
        JNIEnv* env,
        jobject /* this */,
        jstring model_path) {

    if (g_ctx != nullptr) {
        LOGI("既存コンテキストを解放します");
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("モデルを読み込み中: %s", path);

    struct whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false; // Android では CPU 推論

    g_ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(model_path, path);

    if (g_ctx == nullptr) {
        LOGE("モデルの読み込みに失敗しました");
        return 0;
    }

    LOGI("モデルの読み込みに成功しました");
    return reinterpret_cast<jlong>(g_ctx);
}

// ============================================================
// whisper_jni_free: コンテキストを解放
// ============================================================
extern "C"
JNIEXPORT void JNICALL
Java_com_example_whisperapp_WhisperLib_free(
        JNIEnv* env,
        jobject /* this */,
        jlong ctx_ptr) {

    if (ctx_ptr != 0) {
        whisper_context* ctx = reinterpret_cast<whisper_context*>(ctx_ptr);
        whisper_free(ctx);
        LOGI("コンテキストを解放しました");
    }
    if (g_ctx != nullptr) {
        whisper_free(g_ctx);
        g_ctx = nullptr;
    }
}

// ============================================================
// 新しいセグメントのコールバック
// ============================================================
static void whisper_new_segment_callback(
        struct whisper_context* ctx,
        struct whisper_state* /*state*/,
        int n_new,
        void* user_data) {

    if (g_jvm == nullptr || g_callback_obj == nullptr) return;

    JNIEnv* env = nullptr;
    bool attached = false;

    int status = g_jvm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (status == JNI_EDETACHED) {
        g_jvm->AttachCurrentThread(&env, nullptr);
        attached = true;
    }

    if (env == nullptr) return;

    const int n_segments = whisper_full_n_segments(ctx);
    for (int i = n_segments - n_new; i < n_segments; ++i) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        if (text == nullptr) continue;

        jstring j_text = env->NewStringUTF(text);
        if (g_on_new_segment_method != nullptr) {
            env->CallVoidMethod(g_callback_obj, g_on_new_segment_method, j_text);
        }
        env->DeleteLocalRef(j_text);
    }

    if (attached) {
        g_jvm->DetachCurrentThread();
    }
}

// ============================================================
// transcribe: PCM 音声データを文字起こし
// ============================================================
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_whisperapp_WhisperLib_transcribe(
        JNIEnv* env,
        jobject thiz,
        jlong ctx_ptr,
        jfloatArray audio_data,
        jstring language,
        jboolean translate,
        jobject callback) {

    whisper_context* ctx = (ctx_ptr != 0)
        ? reinterpret_cast<whisper_context*>(ctx_ptr)
        : g_ctx;

    if (ctx == nullptr) {
        LOGE("コンテキストが初期化されていません");
        return env->NewStringUTF("[エラー: モデルが読み込まれていません]");
    }

    // PCM データ取得
    jsize audio_length = env->GetArrayLength(audio_data);
    jfloat* audio_ptr = env->GetFloatArrayElements(audio_data, nullptr);

    // コールバック設定
    if (callback != nullptr) {
        g_callback_obj = env->NewGlobalRef(callback);
        jclass cls = env->GetObjectClass(callback);
        g_on_new_segment_method = env->GetMethodID(cls, "onNewSegment", "(Ljava/lang/String;)V");
    }

    // whisper パラメータ設定
    struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);

    const char* lang = env->GetStringUTFChars(language, nullptr);
    params.language      = lang;
    params.translate     = translate;
    params.print_special = false;
    params.print_progress= false;
    params.print_realtime= false;
    params.print_timestamps = true;

    // セグメントコールバック
    if (callback != nullptr) {
        params.new_segment_callback = whisper_new_segment_callback;
        params.new_segment_callback_user_data = nullptr;
    }

    // スレッド数設定 (CPUコア数に応じて調整)
    params.n_threads = std::min(4, (int)std::thread::hardware_concurrency());

    LOGI("文字起こし開始: length=%d, lang=%s, threads=%d",
         audio_length, lang, params.n_threads);

    g_is_running = true;
    int result = whisper_full(ctx, params, audio_ptr, audio_length);
    g_is_running = false;

    env->ReleaseStringUTFChars(language, lang);
    env->ReleaseFloatArrayElements(audio_data, audio_ptr, JNI_ABORT);

    // コールバック解放
    if (g_callback_obj != nullptr) {
        env->DeleteGlobalRef(g_callback_obj);
        g_callback_obj = nullptr;
    }

    if (result != 0) {
        LOGE("whisper_full エラー: %d", result);
        return env->NewStringUTF("[エラー: 文字起こしに失敗しました]");
    }

    // 全セグメントを結合
    std::string full_text;
    const int n_segments = whisper_full_n_segments(ctx);
    for (int i = 0; i < n_segments; ++i) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        if (text != nullptr) {
            full_text += text;
        }
    }

    LOGI("文字起こし完了: %zu 文字", full_text.size());
    return env->NewStringUTF(full_text.c_str());
}

// ============================================================
// getSystemInfo: ハードウェア情報取得
// ============================================================
extern "C"
JNIEXPORT jstring JNICALL
Java_com_example_whisperapp_WhisperLib_getSystemInfo(
        JNIEnv* env,
        jobject /* this */) {
    const char* info = whisper_print_system_info();
    return env->NewStringUTF(info);
}

// ============================================================
// isModelLoaded: モデル読み込み確認
// ============================================================
extern "C"
JNIEXPORT jboolean JNICALL
Java_com_example_whisperapp_WhisperLib_isModelLoaded(
        JNIEnv* env,
        jobject /* this */,
        jlong ctx_ptr) {
    return (ctx_ptr != 0 || g_ctx != nullptr) ? JNI_TRUE : JNI_FALSE;
}
