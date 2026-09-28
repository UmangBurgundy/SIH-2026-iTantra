package org.itantra.speech.vad

import android.content.Context
import android.util.Log
import com.konovalov.vad.webrtc.VadWebRTC
import com.konovalov.vad.webrtc.config.FrameSize
import com.konovalov.vad.webrtc.config.Mode
import com.konovalov.vad.webrtc.config.SampleRate
import org.itantra.speech.utils.AudioUtils

/**
 * Phase 8.3 Tuned Dual-Gate Voice Activity Detector.
 *
 * Gate 1: Adaptive Noise-Floor Energy Gate (asymmetric EMA tracking against ambient acoustic floor)
 * Gate 2: True Google WebRTC VAD Mode 2 (Aggressive, designed for speech recognition in ambient noise)
 *
 * Execution time: < 0.03 ms per 30 ms frame.
 */
class DualGateVad(
    val adaptiveNoiseFloor: AdaptiveNoiseFloor = AdaptiveNoiseFloor(),
    var vadMode: Int = 2                 // WebRTC VAD mode 2 (Aggressive speech recognition)
) {
    companion object {
        private const val TAG = "iTantraVAD"
    }

    private var vadWebRtc: VadWebRTC? = null

    /**
     * When true, activates Phone Call barge-in protection mode:
     * - Elevates the Energy Gate threshold to reject device speaker acoustic leakage.
     * - Freezes background noise floor adaptation so playback audio doesn't inflate ambient noise estimate.
     */
    var bargeInMode: Boolean = false

    /**
     * Multiplier applied to the adaptive speech threshold during barge-in mode (e.g. 1.8x).
     */
    var bargeInThresholdMultiplier: Double = 1.8

    /**
     * Minimum absolute RMS threshold required during barge-in mode, protecting against
     * speaker echo even in quiet environments.
     */
    var bargeInSpeechThreshold: Double = 120.0

    /**
     * Evaluates effective threshold considering bargeInMode.
     */
    val currentEffectiveThreshold: Double
        get() = if (bargeInMode) {
            maxOf(bargeInSpeechThreshold, adaptiveNoiseFloor.speechThreshold * bargeInThresholdMultiplier)
        } else {
            adaptiveNoiseFloor.speechThreshold
        }

    data class Decision(
        val isSpeech: Boolean,
        val rms: Double,
        val noiseFloorRms: Double,
        val thresholdRms: Double,
        val passedEnergyGate: Boolean,
        val passedWebRtcGate: Boolean
    )

    /**
     * Initializes the native WebRTC VAD engine on Android.
     */
    fun initialize(_context: Context? = null) {
        try {
            val mode = when (vadMode) {
                0 -> Mode.NORMAL
                1 -> Mode.LOW_BITRATE
                3 -> Mode.VERY_AGGRESSIVE
                else -> Mode.AGGRESSIVE
            }
            vadWebRtc = VadWebRTC(
                SampleRate.SAMPLE_RATE_16K,
                FrameSize.FRAME_SIZE_480,
                mode,
                0, // 0 speechDurationMs for direct frame-by-frame evaluation
                0  // 0 silenceDurationMs for direct frame-by-frame evaluation
            )
            Log.i(TAG, "Tuned WebRTC VAD initialized successfully (Mode: $mode, 16kHz, 480 samples/30ms)")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize native WebRTC VAD: ${e.message}", e)
        }
    }

    /**
     * Evaluates whether a 30 ms 16-bit PCM frame (960 bytes = 480 samples at 16 kHz)
     * contains active human speech using both adaptive energy and WebRTC gates.
     *
     * @param pcmBytes 960 bytes of 16-bit PCM mono
     * @param precomputedRms Optional pre-calculated RMS from preprocessor
     * @param isSpeechActive True if the pipeline is currently in SPEECH_ACTIVE state
     * @return Decision with full diagnostic breakdown
     */
    fun evaluate(
        pcmBytes: ByteArray,
        precomputedRms: Double? = null,
        isSpeechActive: Boolean = false
    ): Decision {
        val effectiveThreshold = currentEffectiveThreshold
        if (pcmBytes.size < 960) {
            return Decision(false, 0.0, adaptiveNoiseFloor.noiseFloorRms, effectiveThreshold, false, false)
        }

        val rms = precomputedRms ?: AudioUtils.calculateRms(pcmBytes)

        // Gate 1: Adaptive Energy Floor with barge-in protection
        val passedEnergyGate = rms >= effectiveThreshold

        // Gate 2: True native WebRTC VAD
        val passedWebRtcGate = try {
            vadWebRtc?.isSpeech(pcmBytes) ?: (rms >= effectiveThreshold * 1.4)
        } catch (e: Throwable) {
            Log.w(TAG, "VAD evaluation error: ${e.message}")
            false
        }

        val isSpeech = passedEnergyGate && passedWebRtcGate

        // Update noise floor only during non-speech frames and NOT during bargeInMode (remote TTS playing)
        if (!bargeInMode) {
            adaptiveNoiseFloor.update(rms, isSpeechActive || isSpeech)
        }

        return Decision(
            isSpeech = isSpeech,
            rms = rms,
            noiseFloorRms = adaptiveNoiseFloor.noiseFloorRms,
            thresholdRms = effectiveThreshold,
            passedEnergyGate = passedEnergyGate,
            passedWebRtcGate = passedWebRtcGate
        )
    }

    /**
     * Backward-compatible simple boolean check.
     */
    fun isSpeech(pcmBytes: ByteArray): Boolean {
        return evaluate(pcmBytes).isSpeech
    }

    fun reset() {
        adaptiveNoiseFloor.reset()
    }

    fun close() {
        try {
            vadWebRtc?.close()
        } catch (ignored: Exception) {}
        vadWebRtc = null
    }
}
