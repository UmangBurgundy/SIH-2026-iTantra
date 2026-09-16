package org.itantra.speech.vad

/**
 * Real-time Adaptive Noise-Floor Estimator for Phase 8.3.
 *
 * Dynamically computes ambient noise floor energy using asymmetric Exponential
 * Moving Average (EMA) and derives the adaptive speech energy threshold.
 *
 * Solves:
 * - Quiet speech / whispers in a quiet room being ignored by high fixed thresholds.
 * - Stationary fan / traffic noise falsely triggering fixed low thresholds.
 * - Loud speech unintentionally pulling up the background noise estimate.
 */
class AdaptiveNoiseFloor(
    var initialNoiseFloor: Double = 50.0,
    val alphaDown: Double = 0.05,       // Fast adaptation to lower ambient energy (~600ms)
    val alphaUp: Double = 0.005,        // Slow adaptation to higher ambient energy (~6s)
    val snrMargin: Double = 1.5,        // +3.5 dB above estimated noise floor
    val minSpeechThreshold: Double = 60.0,  // Absolute minimum threshold for quiet speech
    val maxSpeechThreshold: Double = 350.0  // Maximum threshold cap to avoid speech rejection
) {
    private var _noiseFloorRms: Double = initialNoiseFloor

    val noiseFloorRms: Double get() = _noiseFloorRms
    val currentNoiseFloor: Double get() = _noiseFloorRms

    val speechThreshold: Double
        get() = (_noiseFloorRms * snrMargin).coerceIn(minSpeechThreshold, maxSpeechThreshold)
    val currentSpeechThreshold: Double get() = speechThreshold

    /**
     * Updates the noise floor estimation with the current frame RMS.
     *
     * @param frameRms Root Mean Square energy of the current 30 ms frame
     * @param isSpeechActive True if the pipeline is currently in an active speech state (freezes update)
     */
    fun update(frameRms: Double, isSpeechActive: Boolean) {
        // Freeze noise floor adaptation while speech is actively occurring
        if (isSpeechActive) return

        if (frameRms < _noiseFloorRms) {
            // Fast fall towards lower ambient background floor
            _noiseFloorRms = (1.0 - alphaDown) * _noiseFloorRms + alphaDown * frameRms
        } else {
            // Slow climb if ambient noise gradually increases (e.g. fan turned on)
            _noiseFloorRms = (1.0 - alphaUp) * _noiseFloorRms + alphaUp * frameRms
        }

        // Clamp noise floor to sensible physical acoustic range [10.0, 300.0]
        _noiseFloorRms = _noiseFloorRms.coerceIn(10.0, 300.0)
    }

    /**
     * Checks if a frame's RMS exceeds the current adaptive speech threshold.
     */
    fun exceedsThreshold(frameRms: Double): Boolean {
        return frameRms >= speechThreshold
    }

    /**
     * Resets noise floor to initial baseline.
     */
    fun reset(baselineRms: Double = initialNoiseFloor) {
        _noiseFloorRms = baselineRms.coerceIn(10.0, 300.0)
    }
}
