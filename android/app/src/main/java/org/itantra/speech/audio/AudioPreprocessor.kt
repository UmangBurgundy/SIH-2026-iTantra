package org.itantra.speech.audio

import org.itantra.speech.utils.AudioUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Real-time Audio Preprocessor for Phase 8.3 front-end speech robustness.
 *
 * Implements:
 * 1. DC Offset Removal & Single-Pole IIR High-Pass Filter (~85 Hz cutoff) to eliminate
 *    sub-100Hz HVAC hum, fan rumble, and mic handling thumps.
 * 2. Clipping detection and dynamic range monitoring.
 * 3. Exact frame RMS and energy calculation.
 *
 * Designed for ultra-low latency (< 0.02 ms per 30 ms frame) on mobile ARM processors.
 */
class AudioPreprocessor(
    val sampleRate: Int = 16000,
    val cutoffHz: Float = 85.0f,
    val clippingThreshold: Short = 32760
) {
    // IIR High-Pass Filter constant: alpha = RC / (RC + dt)
    // At fs = 16000, fc = 85 Hz -> alpha approx 0.9677
    private val alpha: Float = run {
        val dt = 1.0f / sampleRate
        val rc = 1.0f / (2.0f * Math.PI.toFloat() * cutoffHz)
        rc / (rc + dt)
    }

    private var xPrev: Float = 0.0f
    private var yPrev: Float = 0.0f

    data class ProcessedFrame(
        val originalFrame: AudioFrame?,
        val processedPcm: ByteArray,
        val rms: Double,
        val dbfs: Double,
        val isClipped: Boolean,
        val clippingRatio: Float
    ) {
        val filteredPcm: ByteArray get() = processedPcm
        val isClipping: Boolean get() = isClipped
        fun toAudioFrame(): AudioFrame = AudioFrame(processedPcm, processedPcm.size)
    }

    /**
     * Overload for direct PCM byte array processing.
     */
    fun process(pcmBytes: ByteArray): ProcessedFrame {
        return process(AudioFrame(pcmBytes, pcmBytes.size))
    }

    /**
     * Processes a single 30 ms 16-bit PCM audio frame in-place or via output buffer.
     *
     * @param frame Raw captured audio frame (e.g. 960 bytes for 30 ms at 16 kHz)
     * @return ProcessedFrame with filtered audio, RMS, and diagnostics
     */
    fun process(frame: AudioFrame): ProcessedFrame {
        val bytes = frame.data
        if (bytes.size < 2) {
            return ProcessedFrame(frame, bytes, 0.0, -100.0, false, 0.0f)
        }

        val inBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val count = inBuffer.remaining()
        val outBytes = ByteArray(bytes.size)
        val outBuffer = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()

        var sumSquares = 0.0
        var clippedCount = 0

        for (i in 0 until count) {
            val rawSample = inBuffer.get()
            if (rawSample >= clippingThreshold || rawSample <= -clippingThreshold) {
                clippedCount++
            }

            // High-Pass Filter: y[n] = alpha * (y[n-1] + x[n] - x[n-1])
            val x = rawSample.toFloat()
            val y = alpha * (yPrev + x - xPrev)
            xPrev = x
            yPrev = y

            // Clamp back to 16-bit PCM range [-32768, 32767]
            val filteredSample = y.coerceIn(-32768.0f, 32767.0f).toInt().toShort()
            outBuffer.put(filteredSample)

            sumSquares += filteredSample.toDouble() * filteredSample.toDouble()
        }

        val rms = Math.sqrt(sumSquares / count)
        val dbfs = AudioUtils.calculateDbfs(rms)
        val clippingRatio = clippedCount.toFloat() / count.toFloat()
        val isClipped = clippingRatio > 0.005f // Flag if > 0.5% of samples clip

        return ProcessedFrame(
            originalFrame = frame,
            processedPcm = outBytes,
            rms = rms,
            dbfs = dbfs,
            isClipped = isClipped,
            clippingRatio = clippingRatio
        )
    }

    /**
     * Resets filter internal states (e.g. between recording sessions).
     */
    fun reset() {
        xPrev = 0.0f
        yPrev = 0.0f
    }
}
