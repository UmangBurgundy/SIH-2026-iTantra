package org.itantra.speech

import org.itantra.speech.alert.AudioMessage
import org.itantra.speech.alert.AudioPriority
import org.itantra.speech.alert.PredefinedAlert
import org.itantra.speech.transport.MessageProtocol
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.charset.StandardCharsets

/**
 * Unit tests for Phase 8.6 Wi-Fi text communication protocol and transport abstractions.
 */
class WifiTransportUnitTest {

    @Test
    fun testSerializationDeserializationFidelity_NormalHindi() {
        val hindiText = "नमस्ते, आप कैसे हैं? यह एक परीक्षण संदेश है।"
        val message = AudioMessage.createNormal(hindiText, language = "hi")

        val serializedBytes = MessageProtocol.serializeMessage(message)
        val jsonString = String(serializedBytes, StandardCharsets.UTF_8)
        val deserialized = MessageProtocol.deserializeMessage(jsonString)

        assertEquals(message.messageId, deserialized.messageId)
        assertEquals(hindiText, deserialized.text)
        assertEquals("hi", deserialized.language)
        assertEquals(AudioPriority.NORMAL, deserialized.priority)
        assertNull(deserialized.predefinedAlert)
    }

    @Test
    fun testSerializationDeserializationFidelity_EmergencyAlert() {
        val fireAlert = PredefinedAlert.FIRE_EVACUATION
        val message = AudioMessage.fromPredefined(fireAlert, language = "hi")

        val serializedBytes = MessageProtocol.serializeMessage(message)
        val jsonString = String(serializedBytes, StandardCharsets.UTF_8)
        val deserialized = MessageProtocol.deserializeMessage(jsonString)

        assertEquals(fireAlert.alertId, deserialized.messageId)
        assertEquals(fireAlert.hindiText, deserialized.text)
        assertEquals(AudioPriority.ALERT, deserialized.priority)
        assertNotNull(deserialized.predefinedAlert)
        assertEquals(fireAlert.alertId, deserialized.predefinedAlert?.alertId)
    }

    @Test
    fun testLengthPrefixedFraming() {
        val payload = "{\"test\":\"hello world\"}".toByteArray(StandardCharsets.UTF_8)
        val encodedFrame = MessageProtocol.encodeFrame(payload)

        // Frame header is 4 bytes big-endian length
        assertEquals(4 + payload.size, encodedFrame.size)

        val dis = DataInputStream(ByteArrayInputStream(encodedFrame))
        val decodedPayload = MessageProtocol.readFrame(dis)

        assertArrayEquals(payload, decodedPayload)
        assertEquals(0, dis.available())
    }

    @Test
    fun testMultipleSequentialFramesInStream() {
        val msg1 = "First frame".toByteArray()
        val msg2 = "Second frame longer text".toByteArray()
        val frame1 = MessageProtocol.encodeFrame(msg1)
        val frame2 = MessageProtocol.encodeFrame(msg2)

        val combinedStream = ByteArrayInputStream(frame1 + frame2)
        val dis = DataInputStream(combinedStream)

        val decoded1 = MessageProtocol.readFrame(dis)
        val decoded2 = MessageProtocol.readFrame(dis)

        assertArrayEquals(msg1, decoded1)
        assertArrayEquals(msg2, decoded2)
        assertEquals(0, dis.available())
    }

    @Test
    fun testMaxTextLengthSafetyEnforcement() {
        val longText = "अ".repeat(1000) // 1000 characters
        val message = AudioMessage.createNormal(longText)

        val serializedBytes = MessageProtocol.serializeMessage(message)
        val deserialized = MessageProtocol.deserializeMessage(String(serializedBytes))

        assertEquals(MessageProtocol.MAX_TEXT_LENGTH, deserialized.text.length)
        assertEquals(500, deserialized.text.length)
    }

    @Test
    fun testDynamicBandwidthSavingsCalculation() {
        val textBytes = 120 // ~120 bytes JSON payload
        val durationSec = 3.0f // 3 seconds of audio

        val result = MessageProtocol.calculateSavings(textBytes, durationSec)

        // 3.0s * 32,000 B/s = 96,000 bytes PCM
        assertEquals(96000, result.audioBytes)
        assertEquals(120, result.textBytes)

        // Saving ratio = 1 - (120 / 96000) = 1 - 0.00125 = 0.99875 -> 99.875%
        assertTrue("Savings should be > 99%", result.savingPercentage > 99.0)
        assertTrue(result.formattedSummary.contains("saved"))
    }

    @Test
    fun testPingPongFrameCreation() {
        val pingTs = 123456789L
        val pingBytes = MessageProtocol.createPing(pingTs)
        val pingStr = String(pingBytes)

        assertEquals("PING", MessageProtocol.getFrameType(pingStr))
        assertEquals(pingTs, MessageProtocol.extractTimestamp(pingStr))

        val pongBytes = MessageProtocol.createPong(pingTs)
        val pongStr = String(pongBytes)

        assertEquals("PONG", MessageProtocol.getFrameType(pongStr))
        assertEquals(pingTs, MessageProtocol.extractTimestamp(pongStr))
    }
}
