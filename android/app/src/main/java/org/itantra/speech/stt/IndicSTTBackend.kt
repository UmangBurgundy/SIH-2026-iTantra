package org.itantra.speech.stt

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import org.itantra.speech.utils.AudioUtils

/**
 * Dedicated Native Indic Speech-To-Text Backend.
 *
 * Runs AI4Bharat's IndicConformer CTC model (120M parameters, INT8 quantized)
 * on-device using Sherpa-ONNX C++ SIMD acceleration.
 *
 * Outputs native Devanagari script for Hindi ("hi") directly without transliteration,
 * cloud APIs, Python, or LLMs.
 */
class IndicSTTBackend(
    val langCode: String = "hi",
    override val modelName: String = "IndicConformer CTC INT8 (AI4Bharat / Sherpa-ONNX)"
) : STTBackend {

    companion object {
        private const val TAG = "iTantraIndicSTT"
        const val DEFAULT_HINDI_MODEL = "models/indic-hi.int8.onnx"
        const val DEFAULT_INDIC_TOKENS = "models/indic-tokens.txt"
    }

    private var _isLoaded = false
    private var _loadDurationMs: Long = 0
    private var recognizer: OfflineRecognizer? = null

    override val isLoaded: Boolean get() = _isLoaded
    override val loadDurationMs: Long get() = _loadDurationMs

    override fun initialize(context: Context, modelDir: String?): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        Log.i(TAG, "Initializing $modelName (lang=$langCode) [modelDir=$modelDir]...")

        try {
            val isFromAssets = (modelDir == null)
            val modelPath: String
            val tokensPath: String

            if (modelDir != null) {
                val dir = java.io.File(modelDir)
                val sttSubdir = java.io.File(dir, "stt")
                val targetDir = if (sttSubdir.exists()) sttSubdir else dir
                val mFile = targetDir.listFiles { _, name -> name.endsWith(".onnx") }?.firstOrNull()
                    ?: java.io.File(targetDir, "indic-$langCode.int8.onnx")
                val tFile = targetDir.listFiles { _, name -> name.endsWith("tokens.txt") }?.firstOrNull()
                    ?: java.io.File(targetDir, "indic-$langCode-tokens.txt")

                if (!mFile.exists()) {
                    Log.w(TAG, "STT model file not found in ${targetDir.absolutePath}")
                    _isLoaded = false
                    return false
                }
                modelPath = mFile.absolutePath
                tokensPath = tFile.absolutePath
            } else {
                modelPath = DEFAULT_HINDI_MODEL
                tokensPath = DEFAULT_INDIC_TOKENS
            }

            val nemoConfig = OfflineNemoEncDecCtcModelConfig(
                model = modelPath
            )

            val modelConfig = OfflineModelConfig(
                nemo = nemoConfig,
                tokens = tokensPath,
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )

            val config = OfflineRecognizerConfig(
                modelConfig = modelConfig,
                decodingMethod = "greedy_search"
            )

            recognizer = if (isFromAssets) {
                OfflineRecognizer(context.assets, config)
            } else {
                OfflineRecognizer(null, config)
            }
            _loadDurationMs = SystemClock.elapsedRealtime() - t0
            _isLoaded = true
            Log.i(TAG, "$modelName initialized successfully in ${_loadDurationMs}ms")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize Indic OfflineRecognizer: ${e.message}", e)
            _isLoaded = false
            return false
        }
    }

    private var sampleBuffer = FloatArray(16000 * 5) // 5 seconds initial capacity

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
                errorMessage = "Indic STT model not initialized"
            )
        }

        if (pcmAudio.isEmpty()) {
            return STTResult(
                text = "",
                latencyMs = 0,
                audioDurationSec = 0.0,
                rtf = 0.0,
                language = language,
                isSuccess = true
            )
        }

        val requiredFloats = pcmAudio.size / 2
        if (sampleBuffer.size < requiredFloats) {
            sampleBuffer = FloatArray(requiredFloats + 16000)
        }

        val audioDurationSec = requiredFloats.toDouble() / 16000.0
        val t0 = SystemClock.elapsedRealtime()

        try {
            val samples = AudioUtils.pcm16ToFloatArray(pcmAudio, sampleBuffer)
            val stream = rec.createStream()
            stream.acceptWaveform(samples, 16000)
            rec.decode(stream)
            val result = rec.getResult(stream)
            val text = result.text.trim()
            stream.release()

            val latencyMs = SystemClock.elapsedRealtime() - t0
            val rtf = if (audioDurationSec > 0) (latencyMs / 1000.0) / audioDurationSec else 0.0

            Log.i(TAG, "Indic Transcription: raw='$text', audio=${"%.2f".format(audioDurationSec)}s, latency=${latencyMs}ms, rtf=${"%.3f".format(rtf)}")

            return STTResult(
                text = text,
                latencyMs = latencyMs,
                audioDurationSec = audioDurationSec,
                rtf = rtf,
                language = language,
                isSuccess = true
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Indic Transcription error", e)
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
