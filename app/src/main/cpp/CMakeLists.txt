cmake_minimum_required(VERSION 3.22.1)
project("whisperapp")

# ============================================================
# whisper.cpp のソースディレクトリ設定
# whisper.cpp リポジトリをクローンして以下のパスに配置:
#   app/src/main/cpp/whisper.cpp/
# ============================================================
set(WHISPER_CPP_DIR ${CMAKE_SOURCE_DIR}/whisper.cpp)

# whisper.cpp が存在するかチェック
if(NOT EXISTS ${WHISPER_CPP_DIR}/whisper.h)
    message(FATAL_ERROR
        "whisper.cpp が見つかりません。\n"
        "以下のコマンドで取得してください:\n"
        "  cd app/src/main/cpp\n"
        "  git clone https://github.com/ggerganov/whisper.cpp\n"
    )
endif()

# GGML ライブラリのビルド
set(GGML_SOURCES
    ${WHISPER_CPP_DIR}/ggml/src/ggml.c
    ${WHISPER_CPP_DIR}/ggml/src/ggml-alloc.c
    ${WHISPER_CPP_DIR}/ggml/src/ggml-backend.cpp
    ${WHISPER_CPP_DIR}/ggml/src/ggml-quants.c
    ${WHISPER_CPP_DIR}/ggml/src/ggml-threading.cpp
)

# ARM NEON 最適化 (arm64-v8a)
if(${ANDROID_ABI} STREQUAL "arm64-v8a")
    list(APPEND GGML_SOURCES
        ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu/ggml-cpu.c
        ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu/ggml-cpu.cpp
    )
    set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -march=armv8-a+fp+simd")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+fp+simd")
elseif(${ANDROID_ABI} STREQUAL "x86_64")
    list(APPEND GGML_SOURCES
        ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu/ggml-cpu.c
        ${WHISPER_CPP_DIR}/ggml/src/ggml-cpu/ggml-cpu.cpp
    )
endif()

# whisper ライブラリ
add_library(whisper STATIC
    ${GGML_SOURCES}
    ${WHISPER_CPP_DIR}/src/whisper.cpp
)

target_include_directories(whisper PUBLIC
    ${WHISPER_CPP_DIR}
    ${WHISPER_CPP_DIR}/include
    ${WHISPER_CPP_DIR}/ggml/include
    ${WHISPER_CPP_DIR}/ggml/src
)

target_compile_options(whisper PRIVATE
    -O3
    -DNDEBUG
    -DGGML_USE_CPU
)

# JNI ブリッジ ライブラリ
add_library(whisper_jni SHARED
    jni.cpp
)

target_include_directories(whisper_jni PRIVATE
    ${WHISPER_CPP_DIR}
    ${WHISPER_CPP_DIR}/include
    ${WHISPER_CPP_DIR}/ggml/include
)

# Android ライブラリのリンク
find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(whisper_jni
    whisper
    ${log-lib}
    ${android-lib}
)
