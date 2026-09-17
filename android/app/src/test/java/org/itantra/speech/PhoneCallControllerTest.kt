package org.itantra.speech

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import org.itantra.speech.interaction.PhoneCallController
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for PhoneCallController (Phone Call Mode & Full-Duplex Barge-In).
 *
 * Validates:
 * 1. Initial State & Transport Connection Handling
 * 2. Call Lifecycle (startCall, stopCall, guards)
 * 3. Speech Detection, Utterance Finalization & Turn Taking
 * 4. STT Handling (Valid text vs Empty text fallback)
 * 5. Immediate Return to Listening after Send (No WAITING_REMOTE)
 * 6. Remote TTS Playback without Mic Pausing (Full-Duplex)
 * 7. Acoustic Settling Delay (50ms test settling delay)
 * 8. Barge-In Interruption Confirmation (consecutive frames threshold)
 * 9. Stop Remote TTS Callback Invocation on Confirmed Barge-In
 * 10. Transport Disconnection during Active Call & Auto-Resume
 * 11. Error State Transition
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

        controller = PhoneCallController(
            scope = testScope,
            settlingDelayMs = 50L,
            bargeInConfirmationFrames = 3,
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
        assertEquals(PhoneCallController.State.SENDING, controller.state)
        assertEquals(PhoneCallController.Turn.SENDING, controller.turn)

        // 4. Message sent -> Returns directly to LISTENING (no WAITING_REMOTE)
        controller.onMessageSent()
        assertEquals(PhoneCallController.State.LISTENING, controller.state)
        assertEquals(PhoneCallController.Turn.YOUR_TURN, controller.turn)
    }

    @Test
    fun testSttEmptyTextFallback() {
        controller.setTransportConnected(true)
        controller.startCall()

        controller.onSpeechDetected(true)
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
        assertEquals(PhoneCallController.State.PLAYING_REMOTE, controller.state)
        assertEquals(PhoneCallController.Turn.REMOTE_SPEAKING, controller.turn)
        assertTrue(controller.isRemoteTtsPlaying)
        // Mic was NOT requested to pause (Full-duplex design)
        assertEquals(0, pauseListeningCount)

        // Remote TTS finishes -> transitions to RETURNING_TO_LISTEN
        controller.onRemoteTtsFinished()
        assertFalse(controller.isRemoteTtsPlaying)
        assertEquals(PhoneCallController.State.RETURNING_TO_LISTEN, controller.state)
        assertEquals(PhoneCallController.Turn.YOUR_TURN, controller.turn)

        // Wait for settling delay (50ms)
        Thread.sleep(100)
        assertEquals(PhoneCallController.State.LISTENING, controller.state)
        assertEquals(PhoneCallController.Turn.YOUR_TURN, controller.turn)
    }

    @Test
    fun testBargeInInterruptionFlow() {
        controller.setTransportConnected(true)
        controller.startCall()

        // Remote TTS is playing
        controller.onRemoteTtsStarted()
        assertEquals(PhoneCallController.State.PLAYING_REMOTE, controller.state)
        assertEquals(0, stopRemoteTtsCount)

        // 1st speech frame (not enough for 3-frame confirmation threshold)
        controller.onSpeechDetected(true)
        assertEquals(PhoneCallController.State.PLAYING_REMOTE, controller.state)
        assertEquals(0, stopRemoteTtsCount)

        // 2nd speech frame
        controller.onSpeechDetected(true)
        assertEquals(PhoneCallController.State.PLAYING_REMOTE, controller.state)
        assertEquals(0, stopRemoteTtsCount)

        // 3rd consecutive speech frame -> Confirms barge-in!
        controller.onSpeechDetected(true)
        assertEquals(PhoneCallController.State.INTERRUPTED, controller.state)
        assertEquals(PhoneCallController.Turn.INTERRUPTED, controller.turn)
        assertEquals(1, stopRemoteTtsCount) // Remote TTS audio stopped immediately!
        assertFalse(controller.isRemoteTtsPlaying)

        // Utterance finalized for the interrupting speech
        controller.onUtteranceFinalized()
        assertEquals(PhoneCallController.State.PROCESSING_STT, controller.state)
        assertEquals(PhoneCallController.Turn.PROCESSING, controller.turn)

        // STT completes -> SENDING
        controller.onSttComplete(hasValidText = true)
        assertEquals(PhoneCallController.State.SENDING, controller.state)

        // Sent -> Back to LISTENING
        controller.onMessageSent()
        assertEquals(PhoneCallController.State.LISTENING, controller.state)
    }

    @Test
    fun testBargeInResetOnSilence() {
        controller.setTransportConnected(true)
        controller.startCall()

        controller.onRemoteTtsStarted()
        assertEquals(PhoneCallController.State.PLAYING_REMOTE, controller.state)

        // 2 frames of speech then silence
        controller.onSpeechDetected(true)
        controller.onSpeechDetected(true)
        controller.onSpeechDetected(false) // Silence resets counter

        // Next speech frame should start counter from 1 again
        controller.onSpeechDetected(true)
        assertEquals(PhoneCallController.State.PLAYING_REMOTE, controller.state)
        assertEquals(0, stopRemoteTtsCount)

        controller.onSpeechDetected(true)
        assertEquals(PhoneCallController.State.PLAYING_REMOTE, controller.state)
        assertEquals(0, stopRemoteTtsCount)

        // 3rd consecutive frame in the new streak triggers barge-in
        controller.onSpeechDetected(true)
        assertEquals(PhoneCallController.State.INTERRUPTED, controller.state)
        assertEquals(1, stopRemoteTtsCount)
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
