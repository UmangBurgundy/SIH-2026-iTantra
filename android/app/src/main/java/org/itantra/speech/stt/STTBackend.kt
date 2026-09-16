package org.itantra.speech.stt

import android.content.Context

/**
 * Pluggable on-device STT backend interface.
 *
 * Decouples the UI and audio pipeline from the specific ML engine
 * (e.g. Whisper-Tiny ONNX, Sherpa-ONNX Zipformer, or Future IndicConformer).
 */
interface STTBackend {
    val modelName: String
    val isLoaded: Boolean
    val loadDurationMs: Long

    /**
     * Initializes engine and loads model weights from assets or internal storage.
     */
    fun initialize(context: Context, modelDir: String? = null): Boolean

    /**
     * Runs on-device speech-to-text inference on 16 kHz 16-bit mono PCM bytes.
     */
    fun transcribe(pcmAudio: ByteArray, language: String): STTResult

    /**
     * Releases model weights and frees native RAM.
     */
    fun release()
}
