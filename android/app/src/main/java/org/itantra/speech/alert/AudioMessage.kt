package org.itantra.speech.alert

import java.util.UUID

/**
 * Message representation for speech communication and emergency alerts.
 *
 * Designed for immediate local scheduling and reuse in future network layers (Phase 8.6 Wi-Fi / Phase 8.7 BLE).
 *
 * @param messageId Unique identifier for message tracking and deduplication
 * @param text The text to synthesize or speak
 * @param language ISO language code (e.g., "hi" for Hindi)
 * @param priority NORMAL (conversational speech) or ALERT (emergency announcement)
 * @param predefinedAlert Optional reference to a catalog alert if predefined
 * @param timestamp System timestamp in milliseconds when message was created
 */
data class AudioMessage(
    val messageId: String = UUID.randomUUID().toString(),
    val text: String,
    val language: String = "hi",
    val priority: AudioPriority = AudioPriority.NORMAL,
    val predefinedAlert: PredefinedAlert? = null,
    val timestamp: Long = System.currentTimeMillis()
) {
    companion object {
        /**
         * Creates an ALERT message from a predefined emergency alert definition.
         */
        fun fromPredefined(alert: PredefinedAlert, language: String = "hi"): AudioMessage {
            return AudioMessage(
                messageId = alert.alertId,
                text = alert.hindiText,
                language = language,
                priority = AudioPriority.ALERT,
                predefinedAlert = alert,
                timestamp = System.currentTimeMillis()
            )
        }

        /**
         * Creates a custom dynamic ALERT message.
         */
        fun createAlert(text: String, language: String = "hi", customId: String? = null): AudioMessage {
            return AudioMessage(
                messageId = customId ?: "alert_custom_${UUID.randomUUID().toString().take(8)}",
                text = text,
                language = language,
                priority = AudioPriority.ALERT,
                predefinedAlert = null,
                timestamp = System.currentTimeMillis()
            )
        }

        /**
         * Creates a standard NORMAL conversational message.
         */
        fun createNormal(text: String, language: String = "hi", customId: String? = null): AudioMessage {
            return AudioMessage(
                messageId = customId ?: "msg_${UUID.randomUUID().toString().take(8)}",
                text = text,
                language = language,
                priority = AudioPriority.NORMAL,
                predefinedAlert = null,
                timestamp = System.currentTimeMillis()
            )
        }
    }
}
