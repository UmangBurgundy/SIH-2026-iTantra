package org.itantra.speech.interaction

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Controller and state machine for Phase 8.9 Continuous Hands-Free Conversation Mode.
 *
 * Orchestrates the full automatic cycle:
 * LISTEN -> SPEECH_DETECTED -> PROCESSING_STT -> SENDING -> WAITING_REMOTE -> PLAYING_TTS -> RETURNING_TO_LISTEN (200ms settle) -> LISTEN
 *
 * Enforces acoustic feedback lockout during remote TTS playback and provides safe state transitions.
 */
class ContinuousConversationController(
    private val scope: CoroutineScope,
    private val settlingDelayMs: Long = 200L
) {

    companion object {
        private const val TAG = "iTantraConversation"
    }

    enum class State {
        DISCONNECTED,
        IDLE,
        LISTENING,
        SPEECH_DETECTED,
        PROCESSING_STT,
        SENDING,
        WAITING_REMOTE,
        PLAYING_TTS,
        RETURNING_TO_LISTEN,
        ERROR
    }

    enum class Turn {
        YOUR_TURN,
        YOU_SPEAKING,
        PROCESSING,
        SENDING,
        WAITING_FOR_PEER,
        REMOTE_SPEAKING,
        OFFLINE,
        ERROR
    }

    private var currentState: State = State.DISCONNECTED
    var isTransportConnected: Boolean = false
        private set

    var isConversationActive: Boolean = false
        private set

    private var settleJob: Job? = null

    var onStateChanged: ((State, Turn) -> Unit)? = null
    var onRequestResumeListening: (() -> Unit)? = null
    var onRequestPauseListening: (() -> Unit)? = null

    val state: State get() = currentState
    val turn: Turn get() = getTurnForState(currentState)

    fun setTransportConnected(connected: Boolean) {
        if (isTransportConnected == connected) return
        isTransportConnected = connected
        Log.i(TAG, "Conversation: Transport connected changed: $connected")

        if (!connected) {
            if (isConversationActive) {
                Log.w(TAG, "Conversation: Transport unavailable during active conversation (continuing in standalone mode)")
            } else {
                transitionTo(State.DISCONNECTED)
            }
        } else {
            if (currentState == State.DISCONNECTED) {
                if (isConversationActive) {
                    transitionTo(State.LISTENING)
                    onRequestResumeListening?.invoke()
                } else {
                    transitionTo(State.IDLE)
                }
            }
        }
    }

    /**
     * Starts hands-free continuous conversation mode.
     */
    fun startConversation(): Boolean {
        settleJob?.cancel()
        isConversationActive = true
        Log.i(TAG, "Conversation: Started (transportConnected=$isTransportConnected)")
        transitionTo(State.LISTENING)
        onRequestResumeListening?.invoke()
        return true
    }

    /**
     * Stops continuous conversation mode and returns to IDLE.
     */
    fun stopConversation() {
        if (!isConversationActive && currentState == State.IDLE) return
        settleJob?.cancel()
        settleJob = null
        isConversationActive = false
        Log.i(TAG, "Conversation: Stopped")
        onRequestPauseListening?.invoke()
        transitionTo(if (isTransportConnected) State.IDLE else State.DISCONNECTED)
    }

    /**
     * Notifies that VAD has detected speech onset or offset.
     */
    fun onSpeechDetected(isSpeech: Boolean) {
        if (!isConversationActive) return
        if (currentState == State.PLAYING_TTS) {
            // Echo protection: ignore any mic trigger while remote TTS is playing
            return
        }

        if (isSpeech) {
            if (currentState == State.LISTENING || currentState == State.WAITING_REMOTE) {
                Log.i(TAG, "Conversation: Speech detected")
                transitionTo(State.SPEECH_DETECTED)
            }
        } else {
            if (currentState == State.SPEECH_DETECTED) {
                Log.i(TAG, "Conversation: Speech offset detected, waiting for utterance finalization")
                transitionTo(State.LISTENING)
            }
        }
    }

    /**
     * Called when UtteranceSegmenter finalizes an utterance.
     */
    fun onUtteranceFinalized() {
        if (!isConversationActive) return
        if (currentState == State.PLAYING_TTS) return
        Log.i(TAG, "Conversation: Utterance finalized")
        transitionTo(State.PROCESSING_STT)
    }

    /**
     * Called when STT completes with transcribed text.
     */
    fun onSttComplete(hasValidText: Boolean) {
        if (!isConversationActive) return
        if (currentState == State.PLAYING_TTS) return

        if (hasValidText) {
            Log.i(TAG, "Conversation: STT complete -> transitioning to SENDING")
            transitionTo(State.SENDING)
        } else {
            Log.i(TAG, "Conversation: STT produced empty or invalid text -> returning to LISTENING")
            transitionTo(State.LISTENING)
            onRequestResumeListening?.invoke()
        }
    }

    /**
     * Called when the message has been sent through activeTransport.
     */
    fun onMessageSent() {
        if (!isConversationActive) return
        Log.i(TAG, "Conversation: Message sent -> WAITING_REMOTE")
        transitionTo(State.WAITING_REMOTE)
    }

    /**
     * Called when remote message received and TTS begins playback.
     * Enforces echo protection by immediately locking out microphone capture.
     */
    fun onRemoteTtsStarted() {
        settleJob?.cancel()
        Log.i(TAG, "Conversation: Remote message received & TTS started (echo lockout active)")
        onRequestPauseListening?.invoke()
        transitionTo(State.PLAYING_TTS)
    }

    /**
     * Called when TTS finishes speaking through the device speaker.
     * Enforces a settling delay (200ms) before automatically resuming microphone capture.
     */
    fun onRemoteTtsFinished() {
        Log.i(TAG, "Conversation: TTS completed")
        if (!isConversationActive) {
            transitionTo(if (isTransportConnected) State.IDLE else State.DISCONNECTED)
            return
        }

        transitionTo(State.RETURNING_TO_LISTEN)
        settleJob?.cancel()
        settleJob = scope.launch(Dispatchers.Main) {
            Log.i(TAG, "Conversation: Acoustic settling period ($settlingDelayMs ms)...")
            delay(settlingDelayMs)
            if (isConversationActive && isTransportConnected) {
                Log.i(TAG, "Conversation: Resuming listening")
                transitionTo(State.LISTENING)
                onRequestResumeListening?.invoke()
            } else {
                transitionTo(if (isTransportConnected) State.IDLE else State.DISCONNECTED)
            }
        }
    }

    fun onError(errorMessage: String) {
        Log.e(TAG, "Conversation: Error - $errorMessage")
        transitionTo(State.ERROR)
    }

    @Synchronized
    private fun transitionTo(targetState: State) {
        if (currentState == targetState) return
        currentState = targetState
        val turn = getTurnForState(targetState)
        Log.i(TAG, "Conversation: State -> $targetState (Turn: $turn)")
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
            State.WAITING_REMOTE -> Turn.WAITING_FOR_PEER
            State.PLAYING_TTS -> Turn.REMOTE_SPEAKING
            State.RETURNING_TO_LISTEN -> Turn.YOUR_TURN
            State.ERROR -> Turn.ERROR
        }
    }
}
