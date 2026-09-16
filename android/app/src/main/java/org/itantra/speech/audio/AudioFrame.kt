package org.itantra.speech.audio

/**
 * Represents a single fixed-size raw audio frame captured from the microphone.
 *
 * For iTantra:
 * - Sample Rate: 16,000 Hz
 * - Frame Duration: 30 ms
 * - Samples: 480 (16-bit PCM mono)
 * - Raw Bytes: 960 bytes
 */
data class AudioFrame(
    val data: ByteArray,
    val sampleRate: Int = 16000,
    val timestampMs: Long = System.currentTimeMillis()
) {
    val sampleCount: Int get() = data.size / 2

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioFrame
        return data.contentEquals(other.data) && sampleRate == other.sampleRate
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + sampleRate
        return result
    }
}
