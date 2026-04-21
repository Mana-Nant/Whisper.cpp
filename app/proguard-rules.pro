# whisper.cpp JNI クラスは難読化しない
-keep class com.example.whisperapp.WhisperLib { *; }
-keep interface com.example.whisperapp.WhisperLib$TranscribeCallback { *; }

# ViewModel は保持
-keep class com.example.whisperapp.TranscriptionViewModel { *; }

# デフォルトルール
-keepattributes *Annotation*
