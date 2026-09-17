package org.itantra.speech.interaction

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Controller and state machine for Phone Call Mode.
 *
 * Provides a phone-call-like conversational experience with automatic turn-taking
 * and barge-in (interruption) support. Unlike ContinuousConversationController,
 * the microphone remains active during remote TTS playback with elevated VAD
 * thresholds to detect intentional barge-in while preventing acoustic feedback.
 *
 * Key differences from ContinuousConversationController:
 * 1. Mic stays active during remote TTS (bargeInMode=true on UtteranceSegmenter)
 * 2. No WAITING_REMOTE state — returns to LISTENING immediately after sending
 * 3. Speech detection during PLAYING_REMOTE triggers barge-in flow
 * 4. Shorter settling delay (150ms vs 200ms)
 *
 * Flow:
 * LISTENING → SPEECH_DETECTED → PROCESSING_STT → SENDING → LISTENING (cycle)
 *            ↕ (barge-in during PLAYING_REMOTE)
 * PLAYING_REMOTE → INTERRUPTED → PROCESSING_STT → SENDING → LISTENING
 */
class PhoneCallController(
    private val scope: CoroutineScope,
    private val settlingDelayMs: Long = 150L,
    /** Minimum consecutive speech frames needed to trigger barge-in during remote TTS */
    private val bargeInConfirmationFrames: Int = 3,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main
) {

    companion object {
        private const val TAG = "iTantraPhoneCall"
    }

    enum class State {
        DISCONNECTED,
        IDLE,
        LISTENING,
        SPEECH_DETECTED,
        PROCESSING_STT,
        SENDING,
        PLAYING_REMOTE,
        INTERRUPTED,
        RETURNING_TO_LISTEN,
        ERROR
    }

    enum class Turn {
        YOUR_TURN,
        YOU_SPEAKING,
        PROCESSING,
        SENDING,
        REMOTE_SPEAKING,
        INTERRUPTED,
        OFFLINE,
        ERROR
    }

    private var currentState: State = State.DISCONNECTED
    var isTransportConnected: Boolean = false
        private set

    var isCallActive: Boolean = false
        private set

    /** True when remote TTS is playing and mic is in elevated-threshold mode */
    var isRemoteTtsPlaying: Boolean = false
        private set

    private var settleJob: Job? = null

    /** Counter for consecutive speech frames during barge-in detection */
    private var bargeInSpeechFrameCount: Int = 0

    // ── Callbacks ──────────────────────────────────────────────────────────
    var onStateChanged: ((State, Turn) -> Unit)? = null
    var onRequestResumeListening: (() -> Unit)? = null
    var onRequestPauseListening: (() -> Unit)? = null
    /** Called when barge-in is confirmed and remote TTS should be stopped */
    var onRequestStopRemoteTts: (() -> Unit)? = null

    val state: State get() = currentState
    val turn: Turn get() = getTurnForState(currentState)

    // ── Transport Management ───────────────────────────────────────────────

    fun setTransportConnected(connected: Boolean) {
        if (isTransportConnected == connected) return
        isTransportConnected = connected
        Log.i(TAG, "PhoneCall: Transport connected changed: $connected")

        if (!connected) {
            if (isCallActive) {
                Log.w(TAG, "PhoneCall: Transport lost during active call")
            }
            isRemoteTtsPlaying = false
            bargeInSpeechFrameCount = 0
            transitionTo(State.DISCONNECTED)
        } else {
            if (currentState == State.DISCONNECTED) {
                if (isCallActive) {
                    transitionTo(State.LISTENING)
                    onRequestResumeListening?.invoke()
                } else {
                    transitionTo(State.IDLE)
                }
            }
        }
    }

    // ── Call Lifecycle ──────────────────────────────────────────────────────

    /**
     * Starts the phone call mode. Requires an active transport connection.
     */
    fun startCall(): Boolean {
        if (!isTransportConnected) {
            Log.w(TAG, "PhoneCall: Cannot start - transport disconnected")
            return false
        }
        settleJob?.cancel()
        isCallActive = true
        isRemoteTtsPlaying = false
        bargeInSpeechFrameCount = 0
        Log.i(TAG, "PhoneCall: Call started")
        transitionTo(State.LISTENING)
        onRequestResumeListening?.invoke()
        return true
    }

    /**
     * Ends the phone call and returns to IDLE/DISCONNECTED.
     */
    fun stopCall() {
        if (!isCallActive && currentState == State.IDLE) return
        settleJob?.cancel()
        settleJob = null
        isCallActive = false
        isRemoteTtsPlaying = false
        bargeInSpeechFrameCount = 0
        Log.i(TAG, "PhoneCall: Call ended")
        onRequestPauseListening?.invoke()
        transitionTo(if (isTransportConnected) State.IDLE else State.DISCONNECTED)
    }

    // ── Local Speech Events ────────────────────────────────────────────────

    /**
     * Notifies that VAD has detected speech onset or offset.
     *
     * Unlike ContinuousConversationController, this method handles speech
     * detection during PLAYING_REMOTE state for barge-in support.
     */
    fun onSpeechDetected(isSpeech: Boolean) {
        if (!isCallActive) return

        when (currentState) {
            State.PLAYING_REMOTE -> {
                // Barge-in detection: accumulate consecutive speech frames
                if (isSpeech) {
                    bargeInSpeechFrameCount++
                    Log.d(TAG, "PhoneCall: Barge-in speech frame $bargeInSpeechFrameCount/$bargeInConfirmationFrames")
                    if (bargeInSpeechFrameCount >= bargeInConfirmationFrames) {
                        // Confirmed barge-in — interrupt remote TTS
                        performBargeIn()
                    }
                } else {
                    // Reset counter on silence — not a sustained barge-in attempt
                    bargeInSpeechFrameCount = 0
                }
            }

            State.LISTENING, State.RETURNING_TO_LISTEN -> {
                if (isSpeech) {
                    Log.i(TAG, "PhoneCall: Speech detected")
                    transitionTo(State.SPEECH_DETECTED)
                }
            }

            State.SPEECH_DETECTED -> {
                if (!isSpeech) {
                    Log.i(TAG, "PhoneCall: Speech offset, waiting for utterance finalization")
                    // Stay in SPEECH_DETECTED — UtteranceSegmenter will call onUtteranceFinalized
                }
            }

            State.INTERRUPTED -> {
                // Already handling a barge-in, ignore additional speech events
            }

            else -> {
                // Ignore speech during PROCESSING_STT, SENDING, etc.
            }
        }
    }

    /**
     * Called when UtteranceSegmenter finalizes an utterance.
     */
    fun onUtteranceFinalized() {
        if (!isCallActive) return
        if (currentState == State.SPEECH_DETECTED || currentState == State.INTERRUPTED || currentState == State.LISTENING) {
            Log.i(TAG, "PhoneCall: Utterance finalized")
            transitionTo(State.PROCESSING_STT)
        }
    }

    /**
     * Called when STT completes with transcribed text.
     */
    fun onSttComplete(hasValidText: Boolean) {
        if (!isCallActive) return

        if (hasValidText) {
            Log.i(TAG, "PhoneCall: STT complete → transitioning to SENDING")
            transitionTo(State.SENDING)
        } else {
            Log.i(TAG, "PhoneCall: STT produced empty text → returning to LISTENING")
            transitionTo(State.LISTENING)
            onRequestResumeListening?.invoke()
        }
    }

    /**
     * Called when the message has been sent through the active transport.
     * Unlike ContinuousConversationController, returns to LISTENING immediately
     * (no WAITING_REMOTE state) for a more fluid conversation flow.
     */
    fun onMessageSent() {
        if (!isCallActive) return
        Log.i(TAG, "PhoneCall: Message sent → returning to LISTENING")
        transitionTo(State.LISTENING)
        onRequestResumeListening?.invoke()
    }

    // ── Remote TTS Events ──────────────────────────────────────────────────

    /**
     * Called when remote message received and TTS begins playback.
     *
     * Unlike ContinuousConversationController, this does NOT pause the microphone.
     * Instead, it signals the UtteranceSegmenter to enter bargeInMode for elevated
     * speech detection thresholds that prevent acoustic feedback.
     */
    fun onRemoteTtsStarted() {
        settleJob?.cancel()
        isRemoteTtsPlaying = true
        bargeInSpeechFrameCount = 0
        Log.i(TAG, "PhoneCall: Remote TTS started (mic active, bargeIn enabled)")
        // Do NOT pause listening — this is the key difference from ContinuousConversation
        transitionTo(State.PLAYING_REMOTE)
    }

    /**
     * Called when remote TTS finishes playing through the speaker.
     * Applies a short settling delay before fully restoring normal VAD sensitivity.
     */
    fun onRemoteTtsFinished() {
        Log.i(TAG, "PhoneCall: Remote TTS completed")
        isRemoteTtsPlaying = false
        bargeInSpeechFrameCount = 0

        if (!isCallActive) {
            transitionTo(if (isTransportConnected) State.IDLE else State.DISCONNECTED)
            return
        }

        transitionTo(State.RETURNING_TO_LISTEN)
        settleJob?.cancel()
        settleJob = scope.launch(dispatcher) {
            Log.i(TAG, "PhoneCall: Acoustic settling period ($settlingDelayMs ms)...")
            delay(settlingDelayMs)
            if (isCallActive && isTransportConnected) {
                Log.i(TAG, "PhoneCall: Settling complete, resuming normal listening")
                transitionTo(State.LISTENING)
                // Mic is already running, just signal resume for state consistency
                onRequestResumeListening?.invoke()
            } else {
                transitionTo(if (isTransportConnected) State.IDLE else State.DISCONNECTED)
            }
        }
    }

    // ── Barge-In ───────────────────────────────────────────────────────────

    /**
     * Performs the barge-in: stops remote TTS and transitions to processing
     * the interrupting local speech.
     */
    private fun performBargeIn() {
        Log.i(TAG, "PhoneCall: ═══ BARGE-IN CONFIRMED ═══ Interrupting remote TTS")
        isRemoteTtsPlaying = false
        bargeInSpeechFrameCount = 0
        settleJob?.cancel()

        // Stop the remote TTS audio immediately
        onRequestStopRemoteTts?.invoke()

        // Transition to INTERRUPTED state — utterance segmenter will finalize the speech
        transitionTo(State.INTERRUPTED)
    }

    // ── Error ──────────────────────────────────────────────────────────────

    fun onError(errorMessage: String) {
        Log.e(TAG, "PhoneCall: Error - $errorMessage")
        isRemoteTtsPlaying = false
        bargeInSpeechFrameCount = 0
        transitionTo(State.ERROR)
    }

    // ── State Machine ──────────────────────────────────────────────────────

    @Synchronized
    private fun transitionTo(targetState: State) {
        if (currentState == targetState) return
        currentState = targetState
        val turn = getTurnForState(targetState)
        Log.i(TAG, "PhoneCall: State → $targetState (Turn: $turn)")
        onStateChanged?.invoke(targetState, turn)
    }

    private fun getTurnForState(state: State): Turn {
        return when (state) {
            State.DISCONNECTED -> Turn.OFFLINE
            State.IDLE -> Turn.YOUR_TURN
            State.LISTENING -> Turn.YOUR_TURN
            State.SPEECH_DETECTED -> Turn.YOU_SPEAKING
            State.PROCESSING_STT -> Turn.PROCESSING
            State.SENDING -> Turn.SENDING
            State.PLAYING_REMOTE -> Turn.REMOTE_SPEAKING
            State.INTERRUPTED -> Turn.INTERRUPTED
            State.RETURNING_TO_LISTEN -> Turn.YOUR_TURN
            State.ERROR -> Turn.ERROR
        }
    }
}
