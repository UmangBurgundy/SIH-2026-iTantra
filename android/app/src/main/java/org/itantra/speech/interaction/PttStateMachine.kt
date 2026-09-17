package org.itantra.speech.interaction

import android.util.Log

/**
 * Explicit interaction state machine for Phase 8.8 Push-to-Talk communication.
 *
 * Enforces valid state transitions and coordinates turn indicators between
 * local user speech, transmission, and remote TTS playback.
 */
class PttStateMachine {

    companion object {
        private const val TAG = "iTantraPTT"
    }

    enum class State {
        DISCONNECTED,
        IDLE,
        LISTENING,
        PROCESSING,
        SENDING,
        PLAYING,
        ERROR
    }

    enum class ConversationTurn {
        YOUR_TURN,
        YOU_SPEAKING,
        PROCESSING,
        SENDING,
        REMOTE_SPEAKER,
        OFFLINE,
        ERROR
    }

    private var currentState: State = State.DISCONNECTED
    var isTransportConnected: Boolean = false
        private set

    var onStateChanged: ((State, ConversationTurn) -> Unit)? = null

    val state: State get() = currentState

    val canStartListening: Boolean
        get() = isTransportConnected && (currentState == State.IDLE)

    val isListening: Boolean
        get() = currentState == State.LISTENING

    val isPlaying: Boolean
        get() = currentState == State.PLAYING

    val turn: ConversationTurn
        get() = getTurnForState(currentState)

    /**
     * Updates transport connection status. Automatically adjusts between
     * DISCONNECTED and IDLE when in resting states.
     */
    fun setTransportConnected(connected: Boolean) {
        if (isTransportConnected == connected) return
        isTransportConnected = connected
        Log.i(TAG, "PTT: Transport connected state changed: $connected")

        if (!connected) {
            if (currentState == State.IDLE || currentState == State.ERROR) {
                forceTransition(State.DISCONNECTED)
            }
        } else {
            if (currentState == State.DISCONNECTED) {
                forceTransition(State.IDLE)
            }
        }
    }

    /**
     * Attempts to transition to the specified target state.
     * Returns true if the transition was valid and accepted, false otherwise.
     */
    @Synchronized
    fun transitionTo(targetState: State): Boolean {
        if (currentState == targetState) return true

        val valid = when (currentState) {
            State.DISCONNECTED -> {
                targetState == State.IDLE || targetState == State.ERROR
            }
            State.IDLE -> {
                when (targetState) {
                    State.LISTENING -> isTransportConnected
                    State.PLAYING -> true
                    State.DISCONNECTED -> true
                    State.ERROR -> true
                    else -> false
                }
            }
            State.LISTENING -> {
                when (targetState) {
                    State.PROCESSING -> true
                    State.IDLE -> true // Cancelled or no speech detected
                    State.PLAYING -> true // Emergency alert interruption
                    State.DISCONNECTED -> true
                    State.ERROR -> true
                    else -> false
                }
            }
            State.PROCESSING -> {
                when (targetState) {
                    State.SENDING -> true
                    State.IDLE -> true // STT empty or failure handled
                    State.PLAYING -> true
                    State.DISCONNECTED -> true
                    State.ERROR -> true
                    else -> false
                }
            }
            State.SENDING -> {
                when (targetState) {
                    State.IDLE -> true
                    State.PLAYING -> true
                    State.DISCONNECTED -> true
                    State.ERROR -> true
                    else -> false
                }
            }
            State.PLAYING -> {
                when (targetState) {
                    State.IDLE -> isTransportConnected
                    State.DISCONNECTED -> !isTransportConnected
                    State.ERROR -> true
                    else -> false
                }
            }
            State.ERROR -> {
                when (targetState) {
                    State.IDLE -> isTransportConnected
                    State.DISCONNECTED -> true
                    else -> false
                }
            }
        }

        if (!valid) {
            Log.w(TAG, "PTT: Invalid transition rejected: $currentState -> $targetState (transportConnected=$isTransportConnected)")
            return false
        }

        currentState = targetState
        val turn = getTurnForState(targetState)
        Log.i(TAG, "PTT: State changed to $targetState (Turn: $turn)")
        onStateChanged?.invoke(targetState, turn)
        return true
    }

    private fun forceTransition(targetState: State) {
        currentState = targetState
        val turn = getTurnForState(targetState)
        Log.i(TAG, "PTT: State forced to $targetState (Turn: $turn)")
        onStateChanged?.invoke(targetState, turn)
    }

    private fun getTurnForState(state: State): ConversationTurn {
        return when (state) {
            State.DISCONNECTED -> ConversationTurn.OFFLINE
            State.IDLE -> ConversationTurn.YOUR_TURN
            State.LISTENING -> ConversationTurn.YOU_SPEAKING
            State.PROCESSING -> ConversationTurn.PROCESSING
            State.SENDING -> ConversationTurn.SENDING
            State.PLAYING -> ConversationTurn.REMOTE_SPEAKER
            State.ERROR -> ConversationTurn.ERROR
        }
    }
}
