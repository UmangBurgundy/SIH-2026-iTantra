package org.itantra.speech

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.itantra.speech.interaction.PhoneCallConfig
import org.itantra.speech.interaction.PhoneCallController
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for PhoneCallController (Phone Call Mode & Full-Duplex Barge-In).
 */
class PhoneCallControllerTest {

    private val testScope = CoroutineScope(Dispatchers.Default + Job())
    private lateinit var controller: PhoneCallController

    private var lastState: PhoneCallController.State? = null
    private var lastTurn: PhoneCallController.Turn? = null
    private var resumeListeningCount = 0
    private var pauseListeningCount = 0
    private var stopRemoteTtsCount = 0

    @Before
    fun setUp() {
        resumeListeningCount = 0
        pauseListeningCount = 0
        stopRemoteTtsCount = 0
        lastState = null
        lastTurn = null

        val config = PhoneCallConfig(
            settlingDelayMs = 50L,
            bargeInConsecutiveFrames = 3
        )

        controller = PhoneCallController(
            scope = testScope,
            config = config,
            dispatcher = Dispatchers.Default
        ).apply {
            onStateChanged = { state, turn ->
                lastState = state
                lastTurn = turn
            }
            onRequestResumeListening = {
                resumeListeningCount++
            }
            onRequestPauseListening = {
                pauseListeningCount++
            }
            onRequestStopRemoteTts = {
                stopRemoteTtsCount++
            }
        }
    }

    @Test
    fun testInitialState() {
        assertEquals(PhoneCallController.State.DISCONNECTED, controller.state)
        assertEquals(PhoneCallController.Turn.OFFLINE, controller.turn)
        assertFalse(controller.isTransportConnected)
        assertFalse(controller.isCallActive)
        assertFalse(controller.isRemoteTtsPlaying)
    }

    @Test
    fun testTransportConnectionTransitions() {
        controller.setTransportConnected(true)
        assertTrue(controller.isTransportConnected)
        assertEquals(PhoneCallController.State.IDLE, controller.state)
        assertEquals(PhoneCallController.Turn.YOUR_TURN, controller.turn)

        controller.setTransportConnected(false)
        assertFalse(controller.isTransportConnected)
        assertEquals(PhoneCallController.State.DISCONNECTED, controller.state)
        assertEquals(PhoneCallController.Turn.OFFLINE, controller.turn)
    }

    @Test
    fun testStartCallFailsWithoutTransport() {
        assertFalse(controller.isTransportConnected)
        val started = controller.startCall()
        assertFalse(started)
        assertFalse(controller.isCallActive)
        assertEquals(PhoneCallController.State.DISCONNECTED, controller.state)
    }

    @Test
    fun testCallLifecycle() {
        controller.setTransportConnected(true)
        assertEquals(PhoneCallController.State.IDLE, controller.state)

        val started = controller.startCall()
        assertTrue(started)
        assertTrue(controller.isCallActive)
        assertEquals(PhoneCallController.State.LISTENING, controller.state)
        assertEquals(PhoneCallController.Turn.YOUR_TURN, controller.turn)
        assertEquals(1, resumeListeningCount)

        // Stop call
        controller.stopCall()
        assertFalse(controller.isCallActive)
        assertEquals(PhoneCallController.State.IDLE, controller.state)
        assertEquals(PhoneCallController.Turn.YOUR_TURN, controller.turn)
        assertEquals(1, pauseListeningCount)
    }

    @Test
    fun testSpeechDetectionAndUtteranceCycle() {
        controller.setTransportConnected(true)
        controller.startCall()

        // 1. Speech detected
        controller.onSpeechDetected(true)
        assertEquals(PhoneCallController.State.SPEECH_DETECTED, controller.state)
        assertEquals(PhoneCallController.Turn.YOU_SPEAKING, controller.turn)

        // 2. Utterance finalized
        controller.onUtteranceFinalized()
        assertEquals(PhoneCallController.State.PROCESSING_STT, controller.state)
        assertEquals(PhoneCallController.Turn.PROCESSING, controller.turn)

        // 3. STT complete with valid text
        controller.onSttComplete(hasValidText = true)
        assertEquals(PhoneCallController.State.SENDING_TEXT, controller.state)
        assertEquals(PhoneCallController.Turn.SENDING, controller.turn)

        // 4. Message sent -> Returns directly to LISTENING
        controller.onMessageSent()
        assertEquals(PhoneCallController.State.LISTENING, controller.state)
        assertEquals(PhoneCallController.Turn.YOUR_TURN, controller.turn)
    }

    @Test
    fun testSttEmptyTextFallback() {
        controller.setTransportConnected(true)
        controller.startCall()

        controller.onSpeechDetected(true)
        assertEquals(PhoneCallController.State.SPEECH_DETECTED, controller.state)
        controller.onUtteranceFinalized()
        assertEquals(PhoneCallController.State.PROCESSING_STT, controller.state)

        // Empty STT result should fallback to LISTENING immediately
        val initialResumeCount = resumeListeningCount
        controller.onSttComplete(hasValidText = false)
        assertEquals(PhoneCallController.State.LISTENING, controller.state)
        assertEquals(PhoneCallController.Turn.YOUR_TURN, controller.turn)
        assertEquals(initialResumeCount + 1, resumeListeningCount)
    }

    @Test
    fun testRemoteTtsPlaybackAndSettling() {
        controller.setTransportConnected(true)
        controller.startCall()

        // Remote TTS starts
        controller.onRemoteTtsStarted()
        assertEquals(PhoneCallController.State.PLAYING_TTS, controller.state)
        assertEquals(PhoneCallController.Turn.REMOTE_SPEAKING, controller.turn)
        assertTrue(controller.isRemoteTtsPlaying)
        // Mic was NOT requested to pause (Full-duplex design)
        assertEquals(0, pauseListeningCount)

        // Remote TTS finishes -> settling delay then back to LISTENING
        controller.onRemoteTtsFinished()
        assertFalse(controller.isRemoteTtsPlaying)

        // Wait for settling delay (50ms)
        Thread.sleep(150)
        assertEquals(PhoneCallController.State.LISTENING, controller.state)
        assertEquals(PhoneCallController.Turn.YOUR_TURN, controller.turn)
    }

    @Test
    fun testBargeInInterruptionFlow() {
        controller.setTransportConnected(true)
        controller.startCall()

        // Remote TTS is playing
        controller.onRemoteTtsStarted()
        assertEquals(PhoneCallController.State.PLAYING_TTS, controller.state)
        assertEquals(0, stopRemoteTtsCount)

        // UtteranceSegmenter confirms barge-in via onBargeInConfirmed
        controller.onBargeInConfirmed()
        assertEquals(PhoneCallController.State.BARGE_IN, controller.state)
        assertEquals(PhoneCallController.Turn.BARGE_IN, controller.turn)
        assertEquals(1, stopRemoteTtsCount) // Remote TTS audio stopped immediately!
        assertFalse(controller.isRemoteTtsPlaying)

        // Utterance finalized for the interrupting speech
        controller.onUtteranceFinalized()
        assertEquals(PhoneCallController.State.PROCESSING_STT, controller.state)
        assertEquals(PhoneCallController.Turn.PROCESSING, controller.turn)

        // STT completes -> SENDING_TEXT
        controller.onSttComplete(hasValidText = true)
        assertEquals(PhoneCallController.State.SENDING_TEXT, controller.state)

        // Sent -> Back to LISTENING
        controller.onMessageSent()
        assertEquals(PhoneCallController.State.LISTENING, controller.state)
        assertEquals(PhoneCallController.Turn.YOUR_TURN, controller.turn)
    }

    @Test
    fun testTransportLostDuringCallAndReconnection() {
        controller.setTransportConnected(true)
        controller.startCall()
        assertEquals(PhoneCallController.State.LISTENING, controller.state)

        // Transport disconnects during call
        controller.setTransportConnected(false)
        assertFalse(controller.isTransportConnected)
        assertEquals(PhoneCallController.State.DISCONNECTED, controller.state)
        assertEquals(PhoneCallController.Turn.OFFLINE, controller.turn)

        // Reconnection while isCallActive is true restores LISTENING
        controller.setTransportConnected(true)
        assertTrue(controller.isTransportConnected)
        assertEquals(PhoneCallController.State.LISTENING, controller.state)
        assertEquals(PhoneCallController.Turn.YOUR_TURN, controller.turn)
    }

    @Test
    fun testErrorTransition() {
        controller.setTransportConnected(true)
        controller.startCall()

        controller.onError("Socket timeout")
        assertEquals(PhoneCallController.State.ERROR, controller.state)
        assertEquals(PhoneCallController.Turn.ERROR, controller.turn)
    }
}
