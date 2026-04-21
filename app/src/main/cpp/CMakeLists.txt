cmake_minimum_required(VERSION 3.22.1)
project("whisperapp")

set(WHISPER_CPP_DIR ${CMAKE_SOURCE_DIR}/whisper.cpp)

# 新旧両方のパスに対応
if(NOT EXISTS ${WHISPER_CPP_DIR}/include/whisper.h AND NOT EXISTS ${WHISPER_CPP_DIR}/whisper.h)
    message(FATAL_ERROR "whisper.cpp が見つかりません: ${WHISPER_CPP_DIR}")
endif()

# whisper.cpp のビルドシステムをそのまま使う
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
add_subdirectory(${WHISPER_CPP_DIR} whisper_build)

# JNI ブリッジ
add_library(whisper_jni SHARED jni.cpp)

target_include_directories(whisper_jni PRIVATE
    ${WHISPER_CPP_DIR}/include
    ${WHISPER_CPP_DIR}
)

find_library(log-lib log)
target_link_libraries(whisper_jni whisper ${log-lib})
