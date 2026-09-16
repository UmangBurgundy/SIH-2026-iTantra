package org.itantra.speech.stt

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import org.itantra.speech.utils.AudioUtils

/**
 * Native C++ On-Device Speech-To-Text Backend.
 *
 * Runs quantized INT8 Whisper-Tiny directly on ARM64 phone hardware
 * using native SIMD acceleration.
 */
class SherpaOnnxSTTBackend(
    override val modelName: String = "Whisper-Tiny INT8 (Sherpa-ONNX C++)"
) : STTBackend {

    companion object {
        private const val TAG = "iTantraSTT"
    }

    private var _isLoaded = false
    private var _loadDurationMs: Long = 0
    private var recognizer: OfflineRecognizer? = null

    override val isLoaded: Boolean get() = _isLoaded
    override val loadDurationMs: Long get() = _loadDurationMs

    override fun initialize(context: Context, modelDir: String?): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        Log.i(TAG, "Initializing $modelName from Android assets...")

        try {
            val whisperConfig = OfflineWhisperModelConfig(
                encoder = "models/tiny-encoder.int8.onnx",
                decoder = "models/tiny-decoder.int8.onnx",
                language = "en",
                task = "transcribe",
                tailPaddings = -1
            )
            val modelConfig = OfflineModelConfig(
                whisper = whisperConfig,
                tokens = "models/tiny-tokens.txt",
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )
            val config = OfflineRecognizerConfig(
                modelConfig = modelConfig,
                decodingMethod = "greedy_search"
            )

            recognizer = OfflineRecognizer(context.assets, config)
            _loadDurationMs = SystemClock.elapsedRealtime() - t0
            _isLoaded = true
            Log.i(TAG, "$modelName initialized successfully in ${_loadDurationMs}ms")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize OfflineRecognizer: ${e.message}", e)
            _isLoaded = false
            return false
        }
    }

    override fun transcribe(pcmAudio: ByteArray, language: String): STTResult {
        val rec = recognizer
        if (!_isLoaded || rec == null) {
            return STTResult(
                text = "",
                latencyMs = 0,
                audioDurationSec = 0.0,
                rtf = 0.0,
                language = language,
                isSuccess = false,
                errorMessage = "Model not initialized"
            )
        }

        val audioDurationSec = (pcmAudio.size / 2).toDouble() / 16000.0
        val t0 = SystemClock.elapsedRealtime()

        try {
            val samples = AudioUtils.pcm16ToFloatArray(pcmAudio)
            val stream = rec.createStream()
            stream.acceptWaveform(samples, 16000)
            rec.decode(stream)
            val result = rec.getResult(stream)
            val text = result.text.trim()
            stream.release()

            val latencyMs = SystemClock.elapsedRealtime() - t0
            val rtf = if (audioDurationSec > 0) (latencyMs / 1000.0) / audioDurationSec else 0.0

            // Filter out common Whisper ambient noise hallucinations
            val cleaned = text
                .replace(Regex("""(?i)\[(music|water running|rattling|applause|laughter|silence|inaudible)\]"""), "")
                .replace(Regex("""(?i)\((music|water running|water splashing|rattling|applause|laughter|silence|inaudible)\)"""), "")
                .trim()

            val finalText = if (cleaned.isNotEmpty()) cleaned else ""

            Log.i(TAG, "Transcription: raw='$text', cleaned='$finalText', audio=${"%.2f".format(audioDurationSec)}s, latency=${latencyMs}ms, rtf=${"%.3f".format(rtf)}")

            return STTResult(
                text = finalText,
                latencyMs = latencyMs,
                audioDurationSec = audioDurationSec,
                rtf = rtf,
                language = language,
                isSuccess = true
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Transcription error", e)
            val latencyMs = SystemClock.elapsedRealtime() - t0
            return STTResult(
                text = "",
                latencyMs = latencyMs,
                audioDurationSec = audioDurationSec,
                rtf = 0.0,
                language = language,
                isSuccess = false,
                errorMessage = e.message
            )
        }
    }

    override fun release() {
        recognizer?.release()
        recognizer = null
        _isLoaded = false
        Log.i(TAG, "$modelName released from RAM")
    }
}
