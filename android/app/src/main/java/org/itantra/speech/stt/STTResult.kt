package org.itantra.speech.stt

/**
 * Encapsulates the output of an on-device STT inference.
 */
data class STTResult(
    val text: String,
    val latencyMs: Long,
    val audioDurationSec: Double,
    val rtf: Double,
    val language: String,
    val isSuccess: Boolean = true,
    val errorMessage: String? = null
)
