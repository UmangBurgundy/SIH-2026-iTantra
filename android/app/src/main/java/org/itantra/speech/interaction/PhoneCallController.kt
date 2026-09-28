package org.itantra.speech.interaction

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Configuration parameters for Phone Call Mode and False Barge-In Prevention.
 *
 * @param bargeInEnabled Enables/disables acoustic interruption during remote TTS playback.
 * @param bargeInConsecutiveFrames Minimum consecutive speech frames (e.g. 4 frames = 120 ms)
 *        required to confirm intentional user speech before halting remote playback.
 * @param bargeInSpeechThreshold Minimum absolute RMS threshold required during playback.
 * @param bargeInSpeechThresholdMultiplier Multiplier (e.g. 1.8x) applied to adaptive noise floor threshold.
 * @param bargeInMinDurationMs Minimum sustained speech duration in milliseconds (120 ms).
 * @param settlingDelayMs Acoustic settling delay in milliseconds (150 ms) after remote playback stops.
 * @param maxUtteranceMs Upper safety limit to prevent runaway capture (15000 ms).
 */
data class PhoneCallConfig(
    val bargeInEnabled: Boolean = true,
    val bargeInConsecutiveFrames: Int = 4,
    val bargeInSpeechThreshold: Double = 120.0,
    val bargeInSpeechThresholdMultiplier: Double = 1.8,
    val bargeInMinDurationMs: Long = 120L,
    val settlingDelayMs: Long = 150L,
    val maxUtteranceMs: Long = 15000L
)

/**
 * Controller and explicit state machine for Phone Call Mode.
 *
 * Provides a natural, full-duplex conversational experience between two Android devices
 * with automatic turn-taking and robust barge-in (interruption) support.
 *
 * Explicit State Machine States:
 * - IDLE: Call not active, waiting for user to start call.
 * - CALL_CONNECTING: Initiating/handshaking call session.
 * - CALL_CONNECTED: Call connected and active.
 * - LISTENING: Microphone continuously monitoring for speech onset.
 * - SPEECH_DETECTED: Local user speaking, utterance being captured.
 * - PROCESSING_STT: Local utterance finalized, running STT inference.
 * - SENDING_TEXT: Transmitting recognized text over transport.
 * - RECEIVING_TEXT: Incoming text message received from remote peer.
 * - PLAYING_TTS: Remote TTS synthesizing/playing audio through speaker.
 * - BARGE_IN: Local user interrupted remote TTS playback.
 * - CALL_ENDING: Call is terminating and releasing resources.
 * - DISCONNECTED: Underlying transport not connected.
 * - ERROR: Error encountered in pipeline or transport.
 */
class PhoneCallController(
    private val scope: CoroutineScope,
    val config: PhoneCallConfig = PhoneCallConfig(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main
) {

    companion object {
        const val TAG = "PHONE_CALL"
    }

    enum class State {
        DISCONNECTED,
        IDLE,
        CALL_CONNECTING,
        CALL_CONNECTED,
        LISTENING,
        SPEECH_DETECTED,
        PROCESSING_STT,
        SENDING_TEXT,
        RECEIVING_TEXT,
        PLAYING_TTS,
        BARGE_IN,
        CALL_ENDING,
        ERROR
    }

    enum class Turn {
        YOUR_TURN,
        YOU_SPEAKING,
        PROCESSING,
        SENDING,
        RECEIVING,
        REMOTE_SPEAKING,
        BARGE_IN,
        OFFLINE,
        ERROR
    }

    private var currentState: State = State.DISCONNECTED
    var isTransportConnected: Boolean = false
        private set

    var isCallActive: Boolean = false
        private set

    /** True when remote TTS is playing through the speaker */
    var isRemoteTtsPlaying: Boolean = false
        private set

    private var settleJob: Job? = null

    // ── Callbacks ──────────────────────────────────────────────────────────
    var onStateChanged: ((State, Turn) -> Unit)? = null
    var onRequestResumeListening: (() -> Unit)? = null
    var onRequestPauseListening: (() -> Unit)? = null
    /** Called when barge-in is confirmed and remote TTS playback must immediately halt */
    var onRequestStopRemoteTts: (() -> Unit)? = null

    val state: State get() = currentState
    val turn: Turn get() = getTurnForState(currentState)

    // ── Transport Management ───────────────────────────────────────────────

    fun setTransportConnected(connected: Boolean) {
        if (isTransportConnected == connected) return
        isTransportConnected = connected
        Log.i(TAG, "Transport connected changed: $connected")

        if (!connected) {
            if (isCallActive) {
                Log.w(TAG, "PHONE_CALL: transport lost during active call")
            }
            isRemoteTtsPlaying = false
            settleJob?.cancel()
            transitionTo(State.DISCONNECTED)
        } else {
            if (currentState == State.DISCONNECTED) {
                if (isCallActive) {
                    Log.i(TAG, "PHONE_CALL: connected")
                    transitionTo(State.CALL_CONNECTED)
                    transitionTo(State.LISTENING)
                    Log.i(TAG, "PHONE_CALL: listening started")
                    onRequestResumeListening?.invoke()
                } else {
                    transitionTo(State.IDLE)
                }
            }
        }
    }

    // ── Call Lifecycle ──────────────────────────────────────────────────────

    /**
     * Starts Phone Call Mode. Requires active transport connection.
     */
    fun startCall(): Boolean {
        if (!isTransportConnected) {
            Log.w(TAG, "Cannot start call - transport disconnected")
            return false
        }
        settleJob?.cancel()
        isCallActive = true
        isRemoteTtsPlaying = false
        Log.i(TAG, "Starting phone call...")
        transitionTo(State.CALL_CONNECTING)
        transitionTo(State.CALL_CONNECTED)
        Log.i(TAG, "PHONE_CALL: connected")
        transitionTo(State.LISTENING)
        Log.i(TAG, "PHONE_CALL: listening started")
        onRequestResumeListening?.invoke()
        return true
    }

    /**
     * Ends the active phone call and releases audio/state machine resources.
     */
    fun stopCall() {
        if (!isCallActive && currentState == State.IDLE) return
        settleJob?.cancel()
        settleJob = null
        isCallActive = false
        isRemoteTtsPlaying = false
        Log.i(TAG, "PHONE_CALL: call ended")
        transitionTo(State.CALL_ENDING)
        onRequestPauseListening?.invoke()
        transitionTo(if (isTransportConnected) State.IDLE else State.DISCONNECTED)
    }

    // ── Local Speech & Barge-in Events ─────────────────────────────────────

    /**
     * Notifies that VAD has detected speech onset or offset.
     */
    fun onSpeechDetected(isSpeech: Boolean) {
        if (!isCallActive) return

        when (currentState) {
            State.PLAYING_TTS -> {
                if (isSpeech && config.bargeInEnabled) {
                    onBargeInConfirmed()
                }
            }

            State.LISTENING, State.CALL_CONNECTED -> {
                if (isSpeech) {
                    Log.i(TAG, "PHONE_CALL: speech detected")
                    transitionTo(State.SPEECH_DETECTED)
                }
            }

            State.SPEECH_DETECTED -> {
                if (!isSpeech) {
                    Log.i(TAG, "Speech offset, waiting for utterance finalization...")
                }
            }

            State.BARGE_IN -> {
                // Already in barge-in interruption state
            }

            else -> {
                // Ignore speech during STT inference or sending
            }
        }
    }

    /**
     * Confirms that intentional barge-in speech was detected while remote TTS was playing.
     * Halts remote playback immediately and routes the captured speech to STT.
     */
    fun onBargeInConfirmed() {
        if (!isCallActive) return
        if (currentState != State.PLAYING_TTS && !isRemoteTtsPlaying) return
        if (!config.bargeInEnabled) return

        Log.i(TAG, "PHONE_CALL: barge-in detected")
        isRemoteTtsPlaying = false
        settleJob?.cancel()

        transitionTo(State.BARGE_IN)

        // Stop remote TTS immediately
        onRequestStopRemoteTts?.invoke()
        Log.i(TAG, "PHONE_CALL: playback interrupted")
    }

    /**
     * Called when UtteranceSegmenter finalizes an utterance.
     */
    fun onUtteranceFinalized(durationMs: Long = 0L) {
        if (!isCallActive) return
        if (currentState == State.SPEECH_DETECTED || currentState == State.BARGE_IN || currentState == State.LISTENING) {
            Log.i(TAG, "PHONE_CALL: utterance finalized (${durationMs}ms)")
            transitionTo(State.PROCESSING_STT)
            Log.i(TAG, "PHONE_CALL: STT started")
        }
    }

    /**
     * Called when STT completes with transcribed text.
     */
    fun onSttComplete(hasValidText: Boolean, text: String = "", latencyMs: Long = 0L) {
        if (!isCallActive) return

        if (hasValidText) {
            Log.i(TAG, "PHONE_CALL: STT completed (${latencyMs}ms, text: '$text')")
            transitionTo(State.SENDING_TEXT)
            Log.i(TAG, "PHONE_CALL: sending text")
        } else {
            Log.i(TAG, "PHONE_CALL: STT completed (empty text)")
            transitionTo(State.LISTENING)
            Log.i(TAG, "PHONE_CALL: listening started")
            onRequestResumeListening?.invoke()
        }
    }

    /**
     * Called when the text message has been sent through the transport layer.
     */
    fun onMessageSent(sendDurationMs: Long = 0L) {
        if (!isCallActive) return
        Log.i(TAG, "Message transmitted in ${sendDurationMs}ms")
        transitionTo(State.LISTENING)
        Log.i(TAG, "PHONE_CALL: listening started")
        onRequestResumeListening?.invoke()
    }

    // ── Remote Message & Playback Events ───────────────────────────────────

    /**
     * Called when a remote text message is received.
     */
    fun onRemoteMessageReceived(text: String, language: String) {
        if (!isCallActive) return
        Log.i(TAG, "PHONE_CALL: text received (language: $language, text: '$text')")
        transitionTo(State.RECEIVING_TEXT)
    }

    /**
     * Called when TTS begins synthesizing and playing remote speech through the speaker.
     */
    fun onRemoteTtsStarted() {
        if (!isCallActive) return
        settleJob?.cancel()
        isRemoteTtsPlaying = true
        Log.i(TAG, "PHONE_CALL: TTS started")
        Log.i(TAG, "PHONE_CALL: playback started")
        transitionTo(State.PLAYING_TTS)
    }

    /**
     * Called when remote TTS completes playback naturally (not interrupted).
     */
    fun onRemoteTtsFinished() {
        if (currentState == State.BARGE_IN || currentState == State.SPEECH_DETECTED) {
            Log.d(TAG, "Ignoring remote TTS finish callback: Barge-in active")
            return
        }

        Log.i(TAG, "Remote playback completed naturally")
        isRemoteTtsPlaying = false

        if (!isCallActive) {
            transitionTo(if (isTransportConnected) State.IDLE else State.DISCONNECTED)
            return
        }

        settleJob?.cancel()
        settleJob = scope.launch(dispatcher) {
            delay(config.settlingDelayMs)
            if (isCallActive && isTransportConnected && (currentState == State.PLAYING_TTS || currentState == State.RECEIVING_TEXT)) {
                transitionTo(State.LISTENING)
                Log.i(TAG, "PHONE_CALL: listening started")
                onRequestResumeListening?.invoke()
            }
        }
    }

    // ── Error Handling ─────────────────────────────────────────────────────

    fun onError(errorMessage: String) {
        Log.e(TAG, "PHONE_CALL: error: $errorMessage")
        isRemoteTtsPlaying = false
        transitionTo(State.ERROR)
    }

    // ── State Machine Internal ─────────────────────────────────────────────

    @Synchronized
    private fun transitionTo(targetState: State) {
        if (currentState == targetState) return
        val previousState = currentState
        currentState = targetState
        val turn = getTurnForState(targetState)
        Log.i(TAG, "State: $previousState → $targetState (Turn: $turn)")
        onStateChanged?.invoke(targetState, turn)
    }

    private fun getTurnForState(state: State): Turn {
        return when (state) {
            State.DISCONNECTED -> Turn.OFFLINE
            State.IDLE -> Turn.YOUR_TURN
            State.CALL_CONNECTING -> Turn.PROCESSING
            State.CALL_CONNECTED -> Turn.YOUR_TURN
            State.LISTENING -> Turn.YOUR_TURN
            State.SPEECH_DETECTED -> Turn.YOU_SPEAKING
            State.PROCESSING_STT -> Turn.PROCESSING
            State.SENDING_TEXT -> Turn.SENDING
            State.RECEIVING_TEXT -> Turn.RECEIVING
            State.PLAYING_TTS -> Turn.REMOTE_SPEAKING
            State.BARGE_IN -> Turn.BARGE_IN
            State.CALL_ENDING -> Turn.PROCESSING
            State.ERROR -> Turn.ERROR
        }
    }
}
