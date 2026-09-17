# ====================================================================
# iTantra ProGuard / R8 Optimization Rules (Phase 9.1)
# ====================================================================

# 1. Native On-Device Inference Engines (JNI bindings & C++ symbols)
# Microsoft ONNX Runtime Mobile
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Sherpa-ONNX Native C++ SIMD Speech-to-Text Engine
-keep class com.k2fsa.sherpa.onnx.** { *; }
-dontwarn com.k2fsa.sherpa.onnx.**

# Native WebRTC Voice Activity Detection (VAD)
-keep class com.konovalov.vad.webrtc.** { *; }
-dontwarn com.konovalov.vad.webrtc.**

# 2. iTantra Speech Protocol, Models & Serialization
# Preserve JSON protocol serialization models used across Wi-Fi & Bluetooth
-keep class org.itantra.speech.alert.AudioMessage { *; }
-keep class org.itantra.speech.alert.AudioPriority { *; }
-keep class org.itantra.speech.alert.PredefinedAlert { *; }
-keep class org.itantra.speech.alert.PlaybackState { *; }
-keep class org.itantra.speech.stt.STTResult { *; }
-keep class org.itantra.speech.tts.TTSResult { *; }
-keep class org.itantra.speech.transport.** { *; }
-keep class org.itantra.speech.pack.** { *; }
-keep class org.itantra.speech.model.** { *; }
-keep class org.itantra.speech.model.LanguageManager$** { *; }
-keep class org.itantra.speech.utils.MemoryMonitor$** { *; }

# 3. Android Framework & Architecture Components
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep native methods across all classes
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep View constructors for XML inflation
-keepclassmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
