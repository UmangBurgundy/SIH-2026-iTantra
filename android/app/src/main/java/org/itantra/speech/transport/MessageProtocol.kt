package org.itantra.speech.transport

import org.itantra.speech.alert.AudioMessage
import org.itantra.speech.alert.AudioPriority
import org.itantra.speech.alert.PredefinedAlert
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets

/**
 * Framing, serialization, validation, and payload bandwidth calculation for text communication.
 *
 * Framing Format:
 * [4 bytes Big-Endian Length N] [N bytes UTF-8 JSON Payload]
 *
 * Special frame types:
 * - PING frame: {"type":"PING","timestamp":<Long>}
 * - PONG frame: {"type":"PONG","timestamp":<Long>}
 * - MESSAGE frame: {"type":"MESSAGE", ...AudioMessage JSON...}
 */
object MessageProtocol {

    /** Maximum allowed protocol frame size in bytes (64 KB safety limit) */
    const val MAX_FRAME_BYTES = 64 * 1024

    /** Maximum allowed text length for speech messages (application-level limit) */
    const val MAX_TEXT_LENGTH = 500

    /** Standard PCM byte rate for 16 kHz 16-bit Mono audio (Phase 8.4 STT/TTS baseline) */
    const val PCM_BYTES_PER_SECOND = 16000 * 2 // 32,000 bytes/sec

    /**
     * Serializes an AudioMessage into UTF-8 JSON bytes.
     * Enforces MAX_TEXT_LENGTH limit.
     */
    fun serializeMessage(message: AudioMessage): ByteArray {
        val trimmedText = if (message.text.length > MAX_TEXT_LENGTH) {
            message.text.take(MAX_TEXT_LENGTH)
        } else {
            message.text
        }

        val json = JSONObject().apply {
            put("type", "MESSAGE")
            put("messageId", message.messageId)
            put("text", trimmedText)
            put("language", message.language)
            put("priority", message.priority.name)
            put("timestamp", message.timestamp)

            message.predefinedAlert?.let { alert ->
                put("alertId", alert.alertId)
                put("title", alert.title)
            }
        }

        return json.toString().toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Deserializes a JSON string or byte buffer into an AudioMessage.
     */
    fun deserializeMessage(jsonString: String): AudioMessage {
        val json = JSONObject(jsonString)
        val messageId = json.getString("messageId")
        val rawText = json.getString("text")
        val text = if (rawText.length > MAX_TEXT_LENGTH) rawText.take(MAX_TEXT_LENGTH) else rawText
        val language = json.optString("language", "hi")
        val priorityStr = json.optString("priority", AudioPriority.NORMAL.name)
        val priority = try {
            AudioPriority.valueOf(priorityStr)
        } catch (e: Exception) {
            AudioPriority.NORMAL
        }
        val timestamp = json.optLong("timestamp", System.currentTimeMillis())

        val predefinedAlert = if (json.has("alertId")) {
            val alertId = json.getString("alertId")
            PredefinedAlert.findById(alertId)
        } else {
            null
        }

        return AudioMessage(
            messageId = messageId,
            text = text,
            language = language,
            priority = priority,
            predefinedAlert = predefinedAlert,
            timestamp = timestamp
        )
    }

    /**
     * Serializes a PING frame with sender's local nano/millisecond timestamp.
     */
    fun createPing(timestamp: Long): ByteArray {
        val json = JSONObject().apply {
            put("type", "PING")
            put("timestamp", timestamp)
        }
        return json.toString().toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Serializes a PONG response echoing the ping timestamp.
     */
    fun createPong(pingTimestamp: Long): ByteArray {
        val json = JSONObject().apply {
            put("type", "PONG")
            put("timestamp", pingTimestamp)
        }
        return json.toString().toByteArray(StandardCharsets.UTF_8)
    }

    /**
     * Checks if a JSON payload is a PING or PONG frame.
     */
    fun getFrameType(jsonString: String): String {
        return try {
            val json = JSONObject(jsonString)
            json.optString("type", "UNKNOWN")
        } catch (e: Exception) {
            "UNKNOWN"
        }
    }

    fun extractTimestamp(jsonString: String): Long {
        return try {
            val json = JSONObject(jsonString)
            json.optLong("timestamp", 0L)
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Encodes raw payload bytes into a length-prefixed frame:
     * [4-byte Big-Endian Length][Payload Bytes]
     */
    fun encodeFrame(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_FRAME_BYTES) {
            "Payload size ${payload.size} bytes exceeds maximum allowed $MAX_FRAME_BYTES bytes"
        }
        val baos = ByteArrayOutputStream(payload.size + 4)
        val dos = DataOutputStream(baos)
        dos.writeInt(payload.size)
        dos.write(payload)
        dos.flush()
        return baos.toByteArray()
    }

    /**
     * Reads exactly one complete length-prefixed frame from DataInputStream.
     * Throws an exception if length exceeds MAX_FRAME_BYTES or end of stream reached.
     */
    fun readFrame(dis: DataInputStream): ByteArray {
        val length = dis.readInt()
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw IllegalArgumentException("Invalid frame length: $length bytes (max: $MAX_FRAME_BYTES)")
        }
        val buffer = ByteArray(length)
        dis.readFully(buffer)
        return buffer
    }

    /**
     * Dynamically calculates bandwidth savings comparing text payload size against
     * the equivalent raw uncompressed 16 kHz 16-bit mono PCM audio.
     *
     * @param textPayloadBytes Byte count of transmitted JSON text frame
     * @param speechDurationSeconds Estimated or actual duration of the spoken audio in seconds (default: 3.0s)
     * @return BandwidthComparison result with exact numbers
     */
    fun calculateSavings(
        textPayloadBytes: Int,
        speechDurationSeconds: Float = 3.0f
    ): BandwidthComparison {
        val comparableAudioBytes = (speechDurationSeconds * PCM_BYTES_PER_SECOND).toInt()
        val savingRatio = 1.0 - (textPayloadBytes.toDouble() / comparableAudioBytes.toDouble().coerceAtLeast(1.0))
        val savingPercentage = (savingRatio * 100.0).coerceIn(0.0, 99.99)

        return BandwidthComparison(
            textBytes = textPayloadBytes,
            audioBytes = comparableAudioBytes,
            savingPercentage = savingPercentage,
            speechDurationSeconds = speechDurationSeconds
        )
    }

    data class BandwidthComparison(
        val textBytes: Int,
        val audioBytes: Int,
        val savingPercentage: Double,
        val speechDurationSeconds: Float
    ) {
        val formattedSummary: String
            get() = String.format(
                "Payload: %d B (Text) vs ~%.1f KB (%.1fs PCM) → %.2f%% saved",
                textBytes,
                audioBytes / 1024.0,
                speechDurationSeconds,
                savingPercentage
            )
    }
}
