package org.itantra.speech.vad

import org.itantra.speech.audio.AudioFrame
import org.itantra.speech.utils.AudioUtils
import java.io.ByteArrayOutputStream
import java.util.ArrayDeque

/**
 * Phase 8.3 Enhanced Utterance Segmenter.
 *
 * Implements:
 * 1. Transient noise rejection: Requires 3 consecutive speech frames (90 ms) to trigger speech onset.
 * 2. Short pause bridging: Tolerates intra-sentence pauses up to 390 ms (13 frames) without splitting.
 * 3. Pre-speech ring buffer: Keeps 5 frames (150 ms) to capture initial plosive consonants without leading noise.
 * 4. Automatic trailing silence trimming: Strips trailing silence frames upon finalization so the STT
 *    engine receives clean speech ending exactly where the speaker finished.
 * 5. Guard against runaway utterances: Forces finalization at 15s.
 */
class UtteranceSegmenter(
    val vad: DualGateVad = DualGateVad(),
    val speechStartFrames: Int = 2,       // 60 ms continuous speech to trigger onset (rejects clicks/taps)
    val shortPauseFrames: Int = 13,       // 390 ms intra-sentence pause tolerance
    val silenceStopFrames: Int = 25,      // 750 ms continuous silence to finalize utterance
    val prePaddingFrames: Int = 8,        // 240 ms pre-speech buffer (preserves initial consonants)
    val minUtteranceMs: Long = 400,       // Minimum valid speech duration (400 ms)
    val maxUtteranceMs: Long = 15000      // Upper safety limit to prevent runaway capture
) {
    enum class State {
        IDLE,
        SPEECH_ACTIVE
    }

    data class Utterance(
        val pcmData: ByteArray,
        val durationMs: Long,
        val sampleRate: Int = 16000
    ) {
        val sampleCount: Int get() = pcmData.size / 2
    }

    private var currentState = State.IDLE
    private var consecutiveSpeech = 0
    private var consecutiveSilence = 0

    private val preSpeechRingBuffer = ArrayDeque<AudioFrame>(prePaddingFrames + 2)
    private val activeUtteranceBuffer = ByteArrayOutputStream()
    private var utteranceStartTimeMs: Long = 0

    var onSpeechStateChanged: ((Boolean) -> Unit)? = null
    var onUtteranceFinalized: ((Utterance) -> Unit)? = null
    var onVADDecision: ((DualGateVad.Decision) -> Unit)? = null

    /**
     * When true, elevates the consecutive speech-frame threshold required to trigger
     * speech onset from [speechStartFrames] to [bargeInSpeechStartFrames].
     * Used during Phone Call Mode when remote TTS is playing through the speaker
     * to prevent acoustic feedback while still detecting intentional barge-in speech.
     */
    var bargeInMode: Boolean = false

    /** Elevated frame count for barge-in mode (120 ms at 30 ms/frame) */
    var bargeInSpeechStartFrames: Int = 4

    /** Effective speech start threshold considering bargeInMode */
    private val effectiveSpeechStartFrames: Int
        get() = if (bargeInMode) bargeInSpeechStartFrames else speechStartFrames

    val isSpeechActive: Boolean get() = currentState == State.SPEECH_ACTIVE

    fun processFrame(pcmBytes: ByteArray, vadPcm: ByteArray? = null, precomputedRms: Double? = null) {
        processFrame(AudioFrame(pcmBytes, pcmBytes.size), vadPcm, precomputedRms)
    }

    /**
     * Processes a single 30 ms frame.
     *
     * @param frame Raw or preprocessed AudioFrame
     * @param vadPcm Optional preprocessed PCM specifically for VAD analysis (e.g. HPF filtered)
     * @param precomputedRms Optional pre-filtered RMS
     */
    fun processFrame(frame: AudioFrame, vadPcm: ByteArray? = null, precomputedRms: Double? = null) {
        val decision = vad.evaluate(
            pcmBytes = vadPcm ?: frame.data,
            precomputedRms = precomputedRms,
            isSpeechActive = isSpeechActive
        )
        onVADDecision?.invoke(decision)

        val speech = decision.isSpeech

        when (currentState) {
            State.IDLE -> {
                // Maintain pre-speech ring buffer
                if (preSpeechRingBuffer.size >= prePaddingFrames) {
                    preSpeechRingBuffer.pollFirst()
                }
                preSpeechRingBuffer.addLast(frame)

                if (speech) {
                    consecutiveSpeech++
                    if (consecutiveSpeech >= effectiveSpeechStartFrames) {
                        // Confirmed speech onset (3 consecutive frames = 90 ms)
                        currentState = State.SPEECH_ACTIVE
                        consecutiveSilence = 0
                        utteranceStartTimeMs = System.currentTimeMillis()
                        activeUtteranceBuffer.reset()

                        // Flush pre-speech padding into active buffer
                        while (!preSpeechRingBuffer.isEmpty()) {
                            val padFrame = preSpeechRingBuffer.pollFirst()
                            if (padFrame != null) {
                                activeUtteranceBuffer.write(padFrame.data)
                            }
                        }

                        onSpeechStateChanged?.invoke(true)
                    }
                } else {
                    consecutiveSpeech = 0
                }
            }

            State.SPEECH_ACTIVE -> {
                activeUtteranceBuffer.write(frame.data)
                val currentDurationMs = (activeUtteranceBuffer.size() / 32).toLong()

                if (speech) {
                    consecutiveSilence = 0
                    consecutiveSpeech++
                } else {
                    consecutiveSilence++
                    consecutiveSpeech = 0
                }

                // Check termination conditions:
                // 1. Natural conversational pause reached (750 ms continuous silence)
                // 2. Safety max duration reached (15 s)
                val reachedPause = consecutiveSilence >= silenceStopFrames
                val reachedMaxDuration = currentDurationMs >= maxUtteranceMs

                if (reachedPause || reachedMaxDuration) {
                    finalizeUtterance(consecutiveSilence)
                }
            }
        }
    }

    private fun finalizeUtterance(trailingSilenceCount: Int, minAllowedMs: Long = minUtteranceMs) {
        val rawPcm = activeUtteranceBuffer.toByteArray()
        currentState = State.IDLE
        consecutiveSpeech = 0
        consecutiveSilence = 0
        preSpeechRingBuffer.clear()
        activeUtteranceBuffer.reset()

        onSpeechStateChanged?.invoke(false)

        // Trim trailing silence frames so STT doesn't waste compute on trailing room noise
        val cleanPcm = AudioUtils.trimTrailingSilence(
            pcmBytes = rawPcm,
            trailingSilenceFrames = trailingSilenceCount,
            retainTrailingFrames = 5 // Retain ~150 ms natural decay
        )

        val cleanDurationMs = (cleanPcm.size / 32).toLong()

        if (cleanDurationMs >= minAllowedMs && cleanPcm.isNotEmpty()) {
            val utterance = Utterance(pcmData = cleanPcm, durationMs = cleanDurationMs)
            onUtteranceFinalized?.invoke(utterance)
        }
    }

    /**
     * Explicitly signals an utterance endpoint, used by Push-to-Talk on button release.
     * Forces immediate finalization of any active speech buffer without waiting for
     * silence timeout, preventing cutoff of final syllables.
     *
     * @param allowShortUtterance If true, allows slightly shorter utterances (200ms) like "हाँ" or "OK"
     * @return true if an active utterance was finalized, false if no speech was in progress.
     */
    fun forceFinalize(allowShortUtterance: Boolean = true): Boolean {
        if (currentState == State.SPEECH_ACTIVE) {
            finalizeUtterance(
                trailingSilenceCount = consecutiveSilence,
                minAllowedMs = if (allowShortUtterance) 200L else minUtteranceMs
            )
            return true
        }
        return false
    }

    /**
     * Resets internal state machine and buffers.
     */
    fun reset() {
        currentState = State.IDLE
        consecutiveSpeech = 0
        consecutiveSilence = 0
        preSpeechRingBuffer.clear()
        activeUtteranceBuffer.reset()
        vad.reset()
    }
}
