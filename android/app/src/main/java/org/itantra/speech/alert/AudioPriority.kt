package org.itantra.speech.alert

/**
 * Message Priority levels for the iTantra priority audio system.
 */
enum class AudioPriority {
    /**
     * Standard speech communication messages (conversational TTS).
     */
    NORMAL,

    /**
     * Emergency and mission-critical alert messages (preempts NORMAL, non-interruptible by NORMAL).
     */
    ALERT
}

/**
 * Explicit state machine states for the Priority Audio Scheduler.
 */
enum class PlaybackState {
    /**
     * No audio is currently synthesizing or playing.
     */
    IDLE,

    /**
     * A standard conversational TTS message is playing through AudioTrack.
     */
    PLAYING_NORMAL,

    /**
     * A high-priority emergency alert is playing through AudioTrack (non-interruptible by NORMAL).
     */
    PLAYING_ALERT
}
