package org.itantra.speech.tts

import android.content.Context

/**
 * Result of on-device Text-to-Speech synthesis.
 */
data class TTSResult(
    val pcmData: ByteArray,
    val sampleRate: Int = 16000,
    val latencyMs: Long = 0,
    val audioDurationSec: Double = 0.0,
    val rtf: Double = 0.0,
    val isSuccess: Boolean = true,
    val errorMessage: String? = null
) {
    val sampleCount: Int get() = pcmData.size / 2
}

/**
 * Pluggable on-device Text-to-Speech (TTS) backend interface for iTantra.
 *
 * Decouples the UI and communication transport from language-specific
 * neural speech synthesis engines (e.g. Meta MMS-TTS VITS, VITS-Indic, etc.).
 */
interface TTSBackend {
    val modelName: String
    val isLoaded: Boolean
    val loadDurationMs: Long
    val sampleRate: Int

    /**
     * Initializes engine and loads model weights from assets or local storage.
     */
    fun initialize(context: Context, modelDir: String? = null): Boolean

    /**
     * Synthesizes input text to 16 kHz 16-bit mono PCM audio on-device.
     */
    fun synthesize(text: String): TTSResult

    /**
     * Releases model weights, runtime sessions, and native RAM allocations.
     */
    fun release()
}
