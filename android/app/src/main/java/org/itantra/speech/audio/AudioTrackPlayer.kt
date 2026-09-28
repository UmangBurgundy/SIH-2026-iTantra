package org.itantra.speech.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Native Android AudioTrack Player for 16 kHz 16-bit Mono PCM speech output.
 *
 * Implements low-latency streaming playback, clean lifecycle management,
 * and precise timestamp instrumentation for measurable acoustic output start.
 */
class AudioTrackPlayer(
    val sampleRate: Int = 16000
) {
    companion object {
        private const val TAG = "iTantraAudioPlayer"
    }

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    var isPlaying: Boolean = false
        private set

    /**
     * Plays 16 kHz 16-bit PCM byte array asynchronously through Android speaker.
     *
     * @param pcmData Raw little-endian PCM bytes
     * @param onPlaybackStarted Callback invoked at the exact moment AudioTrack begins playback (T3)
     * @param onPlaybackFinished Callback invoked when playback completely ends
     */
    fun play(
        pcmData: ByteArray,
        onPlaybackStarted: (() -> Unit)? = null,
        onPlaybackFinished: (() -> Unit)? = null
    ) {
        if (pcmData.isEmpty()) {
            onPlaybackFinished?.invoke()
            return
        }

        stop()

        playbackJob = scope.launch {
            try {
                val minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val bufferSize = Math.max(minBufferSize * 2, pcmData.size)

                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                audioTrack = track
                isPlaying = true

                track.play()
                val tStart = SystemClock.elapsedRealtime()
                onPlaybackStarted?.invoke()

                // Stream PCM bytes to hardware AudioTrack
                var bytesWritten = 0
                val chunkSize = 2048
                while (isActive && bytesWritten < pcmData.size) {
                    val count = Math.min(chunkSize, pcmData.size - bytesWritten)
                    val written = track.write(pcmData, bytesWritten, count)
                    if (written < 0) {
                        Log.e(TAG, "AudioTrack write error: $written")
                        break
                    }
                    bytesWritten += written
                }

                // Wait for buffer to finish playing
                val audioDurationMs = (pcmData.size.toDouble() / (sampleRate * 2).toDouble() * 1000).toLong()
                val elapsed = SystemClock.elapsedRealtime() - tStart
                val remainingMs = audioDurationMs - elapsed
                if (remainingMs > 0) {
                    kotlinx.coroutines.delay(remainingMs)
                }

                track.stop()
                track.release()
                audioTrack = null
                isPlaying = false

                onPlaybackFinished?.invoke()
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "Playback cancelled or interrupted (barge-in)")
                isPlaying = false
                try {
                    audioTrack?.release()
                } catch (ignored: Throwable) {}
                audioTrack = null
                // Do not invoke onPlaybackFinished on intentional cancellation
            } catch (e: Throwable) {
                Log.e(TAG, "Playback exception: ${e.message}", e)
                isPlaying = false
                try {
                    audioTrack?.release()
                } catch (ignored: Throwable) {}
                audioTrack = null
                onPlaybackFinished?.invoke()
            }
        }
    }

    /**
     * Immediately stops and cancels active playback.
     */
    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        try {
            audioTrack?.let {
                if (it.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    it.stop()
                }
                it.release()
            }
        } catch (ignored: Throwable) {}
        audioTrack = null
        isPlaying = false
    }

    /**
     * Releases all native resources.
     */
    fun release() {
        stop()
    }
}
