package org.itantra.speech.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer

/**
 * Native On-Device Hindi Text-to-Speech Backend using Meta MMS-TTS (VITS INT8 quantized).
 *
 * Runs locally on Android via Microsoft ONNX Runtime Mobile (`com.microsoft.onnxruntime:onnxruntime-android`).
 * Generates 16 kHz 16-bit mono PCM audio directly without Python, cloud APIs, or network dependency.
 */
class HindiMmsTTSBackend(
    override val modelName: String = "Meta MMS-TTS Hindi VITS INT8"
) : TTSBackend {

    companion object {
        private const val TAG = "iTantraHindiTTS"
        const val DEFAULT_MODEL_ASSET = "models/tts/mms-hin.int8.onnx"
        const val DEFAULT_VOCAB_ASSET = "models/tts/mms-hin-vocab.json"
        const val SAMPLE_RATE = 16000

        /**
         * Converts Float32 [-1.0, 1.0] audio array to 16-bit signed PCM byte array (little-endian).
         */
        fun convertFloatToPcm16(floatSamples: FloatArray): ByteArray {
            val pcmBytes = ByteArray(floatSamples.size * 2)
            val buffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)

            var maxVal = 0.0f
            for (sample in floatSamples) {
                val absVal = Math.abs(sample)
                if (absVal > maxVal) maxVal = absVal
            }
            val scale = if (maxVal > 1.0f) (32767.0f / maxVal) else 32767.0f

            for (sample in floatSamples) {
                val intSample = (sample * scale).toInt().coerceIn(-32768, 32767).toShort()
                buffer.putShort(intSample)
            }

            return pcmBytes
        }
    }

    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var normalizer: HindiTextNormalizer? = null

    private var _isLoaded = false
    private var _loadDurationMs = 0L

    override val isLoaded: Boolean get() = _isLoaded
    override val loadDurationMs: Long get() = _loadDurationMs
    override val sampleRate: Int get() = SAMPLE_RATE

    override fun initialize(context: Context, modelDir: String?): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        Log.i(TAG, "Initializing $modelName via zero-copy ModelSource...")

        try {
            val defaultVocabAsset = DEFAULT_VOCAB_ASSET
            val vocabSource = org.itantra.speech.model.ModelSource.resolve(
                modelDir = modelDir,
                subDir = "tts",
                fileNamePrefix = "mms-hin",
                extension = "vocab.json",
                fallbackAssetPath = defaultVocabAsset
            )

            // 1. Load Vocab & Normalizer
            vocabSource.openStream(context).use { stream ->
                normalizer = HindiTextNormalizer.fromInputStream(stream)
            }
            Log.i(TAG, "Loaded Hindi vocab with ${normalizer?.vocabSize} tokens")

            // 2. Initialize ONNX Runtime Environment & Session via direct filesystem path
            val env = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            val defaultModelAsset = DEFAULT_MODEL_ASSET
            val modelSource = org.itantra.speech.model.ModelSource.resolve(
                modelDir = modelDir,
                subDir = "tts",
                fileNamePrefix = "mms-hin",
                extension = ".onnx",
                fallbackAssetPath = defaultModelAsset
            )

            val resolvedModelPath = modelSource.getFilePathOrExtract(context, cacheSubdir = "tts_models")
            val session = env.createSession(resolvedModelPath, sessionOptions)

            ortEnv = env
            ortSession = session
            _loadDurationMs = SystemClock.elapsedRealtime() - t0
            _isLoaded = true

            Log.i(TAG, "$modelName initialized successfully in ${_loadDurationMs}ms (path: $resolvedModelPath)")
            return true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize $modelName: ${e.message}", e)
            _isLoaded = false
            return false
        }
    }

    override fun synthesize(text: String): TTSResult {
        val session = ortSession
        val env = ortEnv
        val norm = normalizer

        if (!_isLoaded || session == null || env == null || norm == null) {
            return TTSResult(
                pcmData = ByteArray(0),
                isSuccess = false,
                errorMessage = "Hindi MMS-TTS backend is not initialized"
            )
        }

        if (text.isBlank()) {
            return TTSResult(
                pcmData = ByteArray(0),
                isSuccess = true
            )
        }

        val t0 = SystemClock.elapsedRealtime()

        try {
            // 1. Tokenize text with VITS blank interleaving
            val tokenIds = norm.tokenize(text)
            if (tokenIds.isEmpty()) {
                return TTSResult(
                    pcmData = ByteArray(0),
                    isSuccess = true
                )
            }

            // 2. Prepare ONNX Input Tensors
            val shape = longArrayOf(1, tokenIds.size.toLong())
            val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), shape)

            val attentionMask = LongArray(tokenIds.size) { 1L }
            val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape)

            val inputs = mapOf(
                "input_ids" to inputIdsTensor,
                "attention_mask" to maskTensor
            )

            // 3. Execute ONNX Neural Inference
            val outputs = session.run(inputs)
            val latencyMs = SystemClock.elapsedRealtime() - t0

            // 4. Extract Waveform Output [1, num_samples]
            val waveformObj = outputs.get(0).value
            val floatSamples: FloatArray = when (waveformObj) {
                is Array<*> -> {
                    @Suppress("UNCHECKED_CAST")
                    (waveformObj as Array<FloatArray>)[0]
                }
                is FloatArray -> waveformObj
                else -> {
                    throw IllegalStateException("Unexpected output tensor type: ${waveformObj?.javaClass}")
                }
            }

            // Clean up ONNX tensors
            inputIdsTensor.close()
            maskTensor.close()
            outputs.close()

            // 5. Convert Float32 audio samples [-1.0f, 1.0f] to 16-bit Mono PCM bytes
            val pcmBytes = convertFloatToPcm16(floatSamples)
            val audioDurationSec = floatSamples.size.toDouble() / SAMPLE_RATE.toDouble()
            val rtf = if (audioDurationSec > 0) (latencyMs.toDouble() / 1000.0) / audioDurationSec else 0.0

            Log.i(TAG, "Synthesized '${text.take(20)}...': ${floatSamples.size} samples (%.2fs), latency=${latencyMs}ms, RTF=%.3f".format(audioDurationSec, rtf))

            return TTSResult(
                pcmData = pcmBytes,
                sampleRate = SAMPLE_RATE,
                latencyMs = latencyMs,
                audioDurationSec = audioDurationSec,
                rtf = rtf,
                isSuccess = true
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Error synthesizing text '$text': ${e.message}", e)
            return TTSResult(
                pcmData = ByteArray(0),
                isSuccess = false,
                errorMessage = e.message
            )
        }
    }

    override fun release() {
        try {
            ortSession?.close()
            ortEnv?.close()
            ortSession = null
            ortEnv = null
            normalizer = null
            _isLoaded = false
            Log.i(TAG, "$modelName released from native RAM")
        } catch (e: Throwable) {
            Log.w(TAG, "Error releasing $modelName: ${e.message}")
        }
    }
}
