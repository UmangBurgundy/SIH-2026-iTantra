package org.itantra.speech.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Low-latency native Android microphone capture pipeline based on AudioRecord.
 *
 * Emits continuous 30 ms (480 samples / 960 bytes) 16 kHz mono 16-bit PCM frames
 * into a non-blocking queue.
 */
class AudioRecorder(
    val sampleRate: Int = 16000,
    val frameDurationMs: Int = 30,
    val queueCapacity: Int = 100
) {
    companion object {
        private const val TAG = "iTantraAudio"
    }

    // 480 samples at 16 kHz = 30 ms. Each 16-bit sample = 2 bytes -> 960 bytes
    val frameSizeBytes: Int = (sampleRate * frameDurationMs / 1000) * 2

    private val isRecording = AtomicBoolean(false)
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null

    // Frame output queue for VAD/pipeline consumption
    val frameQueue: BlockingQueue<AudioFrame> = ArrayBlockingQueue(queueCapacity)

    // Performance metrics
    val totalCapturedFrames = AtomicLong(0)
    val droppedFrames = AtomicLong(0)
    val readErrors = AtomicLong(0)

    val isActive: Boolean get() = isRecording.get()

    /**
     * Starts continuous audio capture on a dedicated high-priority audio thread.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun start(onError: ((String) -> Unit)? = null): Boolean {
        if (isRecording.get()) {
            Log.w(TAG, "AudioRecorder is already active")
            return true
        }

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            val err = "AudioRecord invalid parameters: sampleRate=$sampleRate"
            Log.e(TAG, err)
            onError?.invoke(err)
            return false
        }

        // Allocate at least 4x frame size to avoid hardware DMA underruns
        val bufferSize = Math.max(minBufferSize, frameSizeBytes * 4)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )
        } catch (e: Exception) {
            val err = "Failed to instantiate AudioRecord: ${e.message}"
            Log.e(TAG, err, e)
            onError?.invoke(err)
            return false
        }

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            val err = "AudioRecord failed to initialize. State: ${audioRecord?.state}"
            Log.e(TAG, err)
            audioRecord?.release()
            audioRecord = null
            onError?.invoke(err)
            return false
        }

        try {
            audioRecord?.startRecording()
        } catch (e: Exception) {
            val err = "AudioRecord startRecording exception: ${e.message}"
            Log.e(TAG, err, e)
            audioRecord?.release()
            audioRecord = null
            onError?.invoke(err)
            return false
        }

        isRecording.set(true)
        frameQueue.clear()
        totalCapturedFrames.set(0)
        droppedFrames.set(0)
        readErrors.set(0)

        recordingThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            captureLoop()
        }, "iTantraAudioThread").apply {
            isDaemon = true
            start()
        }

        Log.i(TAG, "AudioRecorder started successfully: 16 kHz Mono PCM16, 30 ms ($frameSizeBytes bytes)")
        return true
    }

    private fun captureLoop() {
        val buffer = ByteArray(frameSizeBytes)
        val record = audioRecord ?: return

        while (isRecording.get()) {
            var bytesRead = 0
            while (bytesRead < frameSizeBytes && isRecording.get()) {
                val result = record.read(buffer, bytesRead, frameSizeBytes - bytesRead)
                if (result > 0) {
                    bytesRead += result
                } else {
                    readErrors.incrementAndGet()
                    Log.w(TAG, "AudioRecord.read returned error code: $result")
                    break
                }
            }

            if (bytesRead == frameSizeBytes && isRecording.get()) {
                totalCapturedFrames.incrementAndGet()
                val frameData = buffer.clone()
                val frame = AudioFrame(data = frameData, sampleRate = sampleRate)

                // Non-blocking offer to prevent audio capture stall
                val offered = frameQueue.offer(frame)
                if (!offered) {
                    droppedFrames.incrementAndGet()
                    Log.w(TAG, "Frame queue overflow! Total dropped frames: ${droppedFrames.get()}")
                }
            }
        }
    }

    /**
     * Safely stops the capture loop and releases audio hardware resources.
     */
    @Synchronized
    fun stop() {
        if (!isRecording.compareAndSet(true, false)) return

        try {
            recordingThread?.join(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        recordingThread = null

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        } finally {
            audioRecord = null
        }

        Log.i(TAG, "AudioRecorder stopped. Captured=${totalCapturedFrames.get()}, Dropped=${droppedFrames.get()}")
    }
}
