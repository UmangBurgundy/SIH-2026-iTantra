package org.itantra.speech.utils

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Utility functions for raw 16-bit PCM audio manipulation, RMS calculation,
 * and format conversion.
 */
object AudioUtils {

    /**
     * Computes the Root Mean Square (RMS) energy of a 16-bit PCM byte array.
     */
    fun calculateRms(pcmBytes: ByteArray): Double {
        if (pcmBytes.isEmpty()) return 0.0
        val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var sumSquares = 0.0
        val count = shortBuffer.remaining()
        if (count == 0) return 0.0

        for (i in 0 until count) {
            val sample = shortBuffer.get()
            sumSquares += sample.toDouble() * sample.toDouble()
        }
        return sqrt(sumSquares / count)
    }

    /**
     * Converts RMS amplitude to decibels relative to full scale (dBFS).
     * 32767 is max amplitude for 16-bit signed PCM.
     */
    fun calculateDbfs(rms: Double): Double {
        if (rms <= 0.0) return -100.0
        val normalized = rms / 32768.0
        return max(-100.0, 20.0 * log10(normalized))
    }

    /**
     * Converts 16-bit little-endian PCM bytes into float array [-1.0f, 1.0f],
     * with automatic soft peak gain normalization to ensure optimal Whisper recognition
     * even when the user speaks quietly or at phone-to-mouth distance.
     */
    /**
     * Converts 16-bit little-endian PCM bytes into float array [-1.0f, 1.0f],
     * with automatic soft peak gain normalization.
     * Overload supporting buffer reuse to eliminate per-turn garbage collection pressure.
     */
    fun pcm16ToFloatArray(
        pcmBytes: ByteArray,
        outBuffer: FloatArray? = null,
        targetPeak: Float = 0.8f
    ): FloatArray {
        val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val count = shortBuffer.remaining()
        val floats = if (outBuffer != null && outBuffer.size >= count) outBuffer else FloatArray(count)
        var maxPeak = 0.0f

        for (i in 0 until count) {
            val f = shortBuffer.get().toFloat() / 32768.0f
            floats[i] = f
            val abs = kotlin.math.abs(f)
            if (abs > maxPeak) {
                maxPeak = abs
            }
        }

        // Apply soft gain if audio signal is quiet (peak < 0.45)
        if (maxPeak in 0.01f..0.45f) {
            val gain = (targetPeak / maxPeak).coerceAtMost(6.0f) // Cap gain at 6x to avoid blowing up noise floor
            for (i in 0 until count) {
                floats[i] = (floats[i] * gain).coerceIn(-1.0f, 1.0f)
            }
        }

        return if (floats === outBuffer && floats.size > count) {
            floats.copyOf(count)
        } else {
            floats
        }
    }

    /**
     * Constructs a standard 44-byte RIFF WAV header for a 16 kHz 16-bit mono PCM payload.
     */
    fun createWavHeader(pcmDataLength: Int, sampleRate: Int = 16000, channels: Int = 1): ByteArray {
        val totalDataLen = pcmDataLength + 36
        val byteRate = sampleRate * channels * 2

        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        buffer.put("RIFF".toByteArray())
        buffer.putInt(totalDataLen)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // Subchunk1Size for PCM
        buffer.putShort(1.toShort()) // AudioFormat 1 = PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort((channels * 2).toShort()) // BlockAlign
        buffer.putShort(16.toShort()) // BitsPerSample
        buffer.put("data".toByteArray())
        buffer.putInt(pcmDataLength)

        return header
    }

    /**
     * Checks if a 16-bit PCM buffer contains clipped / distorted samples (> 99.9% full scale).
     * Returns the ratio of clipped samples (0.0 to 1.0).
     */
    fun detectClippingRatio(pcmBytes: ByteArray, clippingThreshold: Short = 32760): Float {
        if (pcmBytes.isEmpty()) return 0.0f
        val shortBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var clipped = 0
        val count = shortBuffer.remaining()
        if (count == 0) return 0.0f

        for (i in 0 until count) {
            val sample = shortBuffer.get()
            if (sample >= clippingThreshold || sample <= -clippingThreshold) {
                clipped++
            }
        }
        return clipped.toFloat() / count.toFloat()
    }

    /**
     * Trims trailing silence frames from a finalized utterance while retaining a safety decay margin.
     *
     * @param pcmBytes The full accumulated utterance PCM bytes
     * @param trailingSilenceFrames Number of trailing silence frames (each 960 bytes = 30 ms)
     * @param retainTrailingFrames Number of silence frames to keep as natural decay (default 4 = 120 ms)
     * @return Trimmed PCM byte array
     */
    fun trimTrailingSilence(
        pcmBytes: ByteArray,
        trailingSilenceFrames: Int,
        retainTrailingFrames: Int = 4,
        frameSizeBytes: Int = 960
    ): ByteArray {
        val framesToTrim = (trailingSilenceFrames - retainTrailingFrames).coerceAtLeast(0)
        val bytesToTrim = framesToTrim * frameSizeBytes
        if (bytesToTrim <= 0 || bytesToTrim >= pcmBytes.size) {
            return pcmBytes
        }
        val trimmedSize = pcmBytes.size - bytesToTrim
        return pcmBytes.copyOf(trimmedSize)
    }
}
