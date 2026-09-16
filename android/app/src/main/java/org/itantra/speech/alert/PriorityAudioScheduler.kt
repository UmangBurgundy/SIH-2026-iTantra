package org.itantra.speech.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.itantra.speech.audio.AudioTrackPlayer
import org.itantra.speech.tts.TTSBackend
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Detailed telemetry result for an alert playback event.
 */
data class AlertTelemetry(
    val messageId: String,
    val text: String,
    val isCacheHit: Boolean,
    val t0ReceivedMs: Long,
    val t1LookupStartMs: Long,
    val t2LookupEndMs: Long,
    val t3PlaybackStartMs: Long,
    val lookupLatencyMs: Long,
    val prepLatencyMs: Long,
    val alertToPlaybackLatencyMs: Long,
    val audioDurationSec: Double
)

/**
 * Priority Audio Scheduler for iTantra.
 *
 * Implements:
 * 1. Strict priority routing (ALERT > NORMAL).
 * 2. Alert interruption of active normal TTS with normal message discard.
 * 3. Non-interruptibility of active alerts by normal messages.
 * 4. Alert deduplication filter (FIFO for distinct, suppression for identical IDs).
 * 5. AudioFocus management (AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).
 * 6. High-resolution telemetry instrumentation (T0, T1, T2, T3).
 */
class PriorityAudioScheduler(
    private val context: Context,
    private val audioCache: AlertAudioCache,
    var ttsBackend: TTSBackend,
    private val audioTrackPlayer: AudioTrackPlayer
) {
    companion object {
        private const val TAG = "iTantraAudioScheduler"
        private const val DEDUP_WINDOW_MS = 10_000L // 10s duplicate alert suppression window
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val scope = CoroutineScope(Dispatchers.Default)

    // Queues
    val alertQueue = ConcurrentLinkedQueue<AudioMessage>()
    val normalQueue = ConcurrentLinkedQueue<AudioMessage>()

    // Deduplication map: messageId -> timestamp
    private val seenAlerts = ConcurrentHashMap<String, Long>()

    // State machine
    var currentState: PlaybackState = PlaybackState.IDLE
        private set

    @Volatile
    private var isProcessing = false
    private var schedulerJob: Job? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // Callbacks
    var onStateChanged: ((PlaybackState) -> Unit)? = null
    var onAlertTelemetry: ((AlertTelemetry) -> Unit)? = null
    var onDuplicateAlertSuppressed: ((String) -> Unit)? = null
    var onNormalInterrupted: ((AudioMessage) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * Enqueues an incoming audio message according to its priority level.
     *
     * @param message Message to schedule
     */
    fun enqueue(message: AudioMessage) {
        val t0 = SystemClock.elapsedRealtime()

        if (message.priority == AudioPriority.ALERT) {
            // Check deduplication
            val now = System.currentTimeMillis()
            cleanExpiredDeduplicationEntries(now)

            val lastSeen = seenAlerts[message.messageId]
            if (lastSeen != null && (now - lastSeen) < DEDUP_WINDOW_MS) {
                Log.w(TAG, "Duplicate ALERT suppressed for ID: '${message.messageId}'")
                onDuplicateAlertSuppressed?.invoke(message.messageId)
                return
            }

            seenAlerts[message.messageId] = now
            Log.i(TAG, "Enqueued ALERT '${message.messageId}' [${message.text.take(20)}...] at T0=$t0")

            // If a NORMAL message is currently playing, immediately preempt it
            if (currentState == PlaybackState.PLAYING_NORMAL) {
                Log.i(TAG, "ALERT preempting active NORMAL playback! Stopping AudioTrack...")
                audioTrackPlayer.stop()
                // Interrupted normal message is discarded per policy
            }

            alertQueue.offer(message)
        } else {
            // NORMAL message
            if (currentState == PlaybackState.PLAYING_ALERT) {
                Log.w(TAG, "NORMAL message rejected/dropped while ALERT is actively playing")
                return
            }
            Log.i(TAG, "Enqueued NORMAL message '${message.messageId}'")
            normalQueue.offer(message)
        }

        triggerDispatch()
    }

    /**
     * Triggers the scheduler dispatch loop if not already running.
     */
    @Synchronized
    private fun triggerDispatch() {
        if (isProcessing) return
        isProcessing = true

        schedulerJob = scope.launch {
            try {
                processNextMessage()
            } finally {
                isProcessing = false
            }
        }
    }

    /**
     * Main dispatch decision loop.
     */
    private suspend fun processNextMessage() {
        while (scope.isActive) {
            // 1. Check ALERT queue first (Absolute Priority)
            val nextAlert = alertQueue.poll()
            if (nextAlert != null) {
                playAlert(nextAlert)
                continue
            }

            // 2. If no alerts, check NORMAL queue
            val nextNormal = normalQueue.poll()
            if (nextNormal != null) {
                playNormal(nextNormal)
                continue
            }

            // Both queues empty: return to IDLE
            updateState(PlaybackState.IDLE)
            abandonAudioFocus()
            break
        }
    }

    /**
     * Plays a high-priority ALERT message.
     */
    private suspend fun playAlert(message: AudioMessage) {
        updateState(PlaybackState.PLAYING_ALERT)
        requestAudioFocus()

        val t0 = SystemClock.elapsedRealtime()
        val t1 = SystemClock.elapsedRealtime()

        // 1. Cache lookup
        val cacheKey = message.predefinedAlert?.alertId ?: message.messageId
        val cached = audioCache.get(cacheKey)
        val t2 = SystemClock.elapsedRealtime()

        val pcmData: ByteArray
        val isCacheHit: Boolean

        if (cached != null) {
            pcmData = cached.pcmData
            isCacheHit = true
        } else {
            // Cache miss: synthesize on-the-fly via MMS-TTS
            Log.w(TAG, "Cache MISS for alert '${message.messageId}'. Synthesizing fallback...")
            val synthResult = withContext(Dispatchers.Default) {
                ttsBackend.synthesize(message.text)
            }
            if (!synthResult.isSuccess || synthResult.pcmData.isEmpty()) {
                val err = "Alert synthesis failed for '${message.messageId}': ${synthResult.errorMessage}"
                Log.e(TAG, err)
                onError?.invoke(err)
                return
            }
            pcmData = synthResult.pcmData
            isCacheHit = false
            // Dynamically cache synthesized audio
            audioCache.put(cacheKey, pcmData)
        }

        val audioDurationSec = pcmData.size.toDouble() / (AlertAudioCache.SAMPLE_RATE * 2).toDouble()

        // 2. Play via AudioTrackPlayer and await completion
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
            var resumed = false

            audioTrackPlayer.play(
                pcmData = pcmData,
                onPlaybackStarted = {
                    val t3 = SystemClock.elapsedRealtime()
                    val lookupLatency = t2 - t1
                    val prepLatency = t3 - t2
                    val totalLatency = t3 - t0

                    Log.i(TAG, "ALERT Playback Started: T0=$t0, T1=$t1, T2=$t2, T3=$t3. Lookup=${lookupLatency}ms, Prep=${prepLatency}ms, Total=${totalLatency}ms (Hit=$isCacheHit)")

                    val telemetry = AlertTelemetry(
                        messageId = message.messageId,
                        text = message.text,
                        isCacheHit = isCacheHit,
                        t0ReceivedMs = t0,
                        t1LookupStartMs = t1,
                        t2LookupEndMs = t2,
                        t3PlaybackStartMs = t3,
                        lookupLatencyMs = lookupLatency,
                        prepLatencyMs = prepLatency,
                        alertToPlaybackLatencyMs = totalLatency,
                        audioDurationSec = audioDurationSec
                    )
                    onAlertTelemetry?.invoke(telemetry)
                },
                onPlaybackFinished = {
                    Log.i(TAG, "ALERT Playback Completed for ID: '${message.messageId}'")
                    if (!resumed) {
                        resumed = true
                        continuation.resumeWith(Result.success(Unit))
                    }
                }
            )

            continuation.invokeOnCancellation {
                audioTrackPlayer.stop()
            }
        }
    }

    /**
     * Plays a standard NORMAL conversational message.
     */
    private suspend fun playNormal(message: AudioMessage) {
        updateState(PlaybackState.PLAYING_NORMAL)

        // Synthesize normal message via MMS-TTS
        val synthResult = withContext(Dispatchers.Default) {
            ttsBackend.synthesize(message.text)
        }

        // If an alert arrived while synthesis was in flight, discard normal message immediately
        if (alertQueue.isNotEmpty() || currentState == PlaybackState.PLAYING_ALERT) {
            Log.w(TAG, "NORMAL message '${message.messageId}' discarded due to incoming ALERT during synthesis")
            onNormalInterrupted?.invoke(message)
            return
        }

        if (!synthResult.isSuccess || synthResult.pcmData.isEmpty()) {
            onError?.invoke("Normal TTS synthesis failed: ${synthResult.errorMessage}")
            return
        }

        val pcmData = synthResult.pcmData

        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { continuation ->
            var resumed = false

            audioTrackPlayer.play(
                pcmData = pcmData,
                onPlaybackStarted = {
                    Log.i(TAG, "NORMAL Playback Started for '${message.messageId}'")
                },
                onPlaybackFinished = {
                    Log.i(TAG, "NORMAL Playback Finished for '${message.messageId}'")
                    if (!resumed) {
                        resumed = true
                        continuation.resumeWith(Result.success(Unit))
                    }
                }
            )

            continuation.invokeOnCancellation {
                audioTrackPlayer.stop()
            }
        }
    }

    private fun updateState(newState: PlaybackState) {
        if (currentState != newState) {
            currentState = newState
            Log.i(TAG, "Scheduler State Transition -> $newState")
            onStateChanged?.invoke(newState)
        }
    }

    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                    .setAudioAttributes(playbackAttributes)
                    .setOnAudioFocusChangeListener { focusChange ->
                        Log.i(TAG, "Audio focus changed: $focusChange")
                    }
                    .build()

                audioFocusRequest = focusRequest
                val result = audioManager.requestAudioFocus(focusRequest)
                Log.i(TAG, "Requested AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE: result=$result")
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_NOTIFICATION,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
                )
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error requesting audio focus: ${e.message}")
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let {
                    audioManager.abandonAudioFocusRequest(it)
                    audioFocusRequest = null
                }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error abandoning audio focus: ${e.message}")
        }
    }

    private fun cleanExpiredDeduplicationEntries(currentTimeMs: Long) {
        val iterator = seenAlerts.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (currentTimeMs - entry.value > DEDUP_WINDOW_MS) {
                iterator.remove()
            }
        }
    }

    /**
     * Clears all pending queues and immediately halts audio playback.
     */
    fun stop() {
        alertQueue.clear()
        normalQueue.clear()
        audioTrackPlayer.stop()
        schedulerJob?.cancel()
        schedulerJob = null
        updateState(PlaybackState.IDLE)
        abandonAudioFocus()
    }

    /**
     * Releases scheduler resources.
     */
    fun release() {
        stop()
        seenAlerts.clear()
    }
}
