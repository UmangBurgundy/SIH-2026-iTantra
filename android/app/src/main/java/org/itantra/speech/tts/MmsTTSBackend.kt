package org.itantra.speech.tts

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.LongBuffer
import java.text.Normalizer
import org.json.JSONObject
import org.itantra.speech.model.ModelSource

/**
 * Generalized On-Device Meta MMS-TTS VITS Backend.
 *
 * Runs locally on Android via Microsoft ONNX Runtime Mobile (`com.microsoft.onnxruntime:onnxruntime-android`).
 * Supports:
 * 1. Bundled assets (e.g. Hindi from assets/models/tts/)
 * 2. Downloaded persistent language packs (from persistent phone storage `modelDir`)
 *
 * Applicable to all 10 target languages (Hindi, Gujarati, Marathi, Kannada, Malayalam,
 * Tamil, Telugu, Odia, Bengali, English).
 */
open class MmsTTSBackend(
    override val modelName: String,
    val langCode: String,
    val assetModelPath: String? = null,
    val assetVocabPath: String? = null
) : TTSBackend {

    companion object {
        private const val TAG = "iTantraMmsTTS"
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
    private var tokenMap: Map<String, Long> = emptyMap()

    private var _isLoaded = false
    private var _loadDurationMs = 0L

    override val isLoaded: Boolean get() = _isLoaded
    override val loadDurationMs: Long get() = _loadDurationMs
    override val sampleRate: Int get() = SAMPLE_RATE

    override fun initialize(context: Context, modelDir: String?): Boolean {
        val t0 = SystemClock.elapsedRealtime()
        Log.i(TAG, "Initializing $modelName (lang=$langCode) [modelDir=$modelDir]...")

        try {
            val env = OrtEnvironment.getEnvironment()
            val sessionOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }

            // 1. Resolve Vocab & Load Character-to-Token Map
            val defaultVocabAsset = assetVocabPath ?: "models/tts/mms-hin-vocab.json"
            val vocabSource = ModelSource.resolve(
                modelDir = modelDir,
                subDir = "tts",
                fileNamePrefix = "mms-",
                extension = "vocab.json",
                fallbackAssetPath = defaultVocabAsset
            )

            vocabSource.openStream(context).use { stream ->
                val jsonStr = stream.bufferedReader().readText()
                val jsonObj = JSONObject(jsonStr)
                val map = mutableMapOf<String, Long>()
                val keys = jsonObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    map[key] = jsonObj.getLong(key)
                }
                tokenMap = map
            }
            Log.i(TAG, "Loaded vocab for $langCode: ${tokenMap.size} tokens (source: ${vocabSource.identifier})")

            // 2. Resolve ONNX Model & Create Session without JVM byte[] allocation
            val defaultModelAsset = assetModelPath ?: "models/tts/mms-hin.int8.onnx"
            val modelSource = ModelSource.resolve(
                modelDir = modelDir,
                subDir = "tts",
                fileNamePrefix = "mms-",
                extension = ".onnx",
                fallbackAssetPath = defaultModelAsset
            )

            val resolvedModelPath = modelSource.getFilePathOrExtract(context, cacheSubdir = "tts_models")
            Log.i(TAG, "Loading TTS model via direct filesystem path: $resolvedModelPath (source: ${modelSource.identifier})")
            val session = env.createSession(resolvedModelPath, sessionOptions)

            ortEnv = env
            ortSession = session
            _loadDurationMs = SystemClock.elapsedRealtime() - t0
            _isLoaded = true
            Log.i(TAG, "$modelName initialized successfully in ${_loadDurationMs}ms")
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

        if (!_isLoaded || session == null || env == null || tokenMap.isEmpty()) {
            return TTSResult(
                pcmData = ByteArray(0),
                isSuccess = false,
                errorMessage = "TTS backend for '$langCode' is not initialized"
            )
        }

        if (text.isBlank()) {
            return TTSResult(pcmData = ByteArray(0), isSuccess = true)
        }

        val t0 = SystemClock.elapsedRealtime()

        try {
            // 1. Normalize and Tokenize with VITS Blank Interleaving: [0, t1, 0, t2, 0, ..., tn, 0]
            val tokenIds = tokenizeText(text)
            if (tokenIds.isEmpty()) {
                return TTSResult(pcmData = ByteArray(0), isSuccess = true)
            }

            // 2. Prepare ONNX Input Tensors
            val shape = longArrayOf(1, tokenIds.size.toLong())
            val inputIdsTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokenIds), shape)
            val attentionMask = LongArray(tokenIds.size) { 1L }
            val maskTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(attentionMask), shape)
            val scales = floatArrayOf(0.667f, 1.0f, 0.8f) // noise_scale, length_scale, noise_scale_w
            val scalesTensor = OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(scales), longArrayOf(3))

            val inputs = mutableMapOf<String, OnnxTensor>(
                "input_ids" to inputIdsTensor,
                "attention_mask" to maskTensor
            )
            if (session.inputNames.contains("scales")) {
                inputs["scales"] = scalesTensor
            }

            // 3. Run Inference
            val outputs = session.run(inputs)
            val outputTensor = outputs[0] as OnnxTensor
            val audioData = outputTensor.value

            val floatSamples: FloatArray = when (audioData) {
                is Array<*> -> {
                    val first = audioData[0]
                    when (first) {
                        is FloatArray -> first
                        is Array<*> -> (first[0] as? FloatArray) ?: FloatArray(0)
                        else -> FloatArray(0)
                    }
                }
                is FloatArray -> audioData
                else -> FloatArray(0)
            }

            val pcmBytes = convertFloatToPcm16(floatSamples)
            val latencyMs = SystemClock.elapsedRealtime() - t0
            val audioDurationSec = floatSamples.size.toDouble() / SAMPLE_RATE.toDouble()
            val rtf = if (audioDurationSec > 0) (latencyMs / 1000.0) / audioDurationSec else 0.0

            inputIdsTensor.close()
            maskTensor.close()
            scalesTensor.close()
            outputs.close()

            Log.i(TAG, "TTS ($langCode): text='$text', samples=${floatSamples.size}, latency=${latencyMs}ms, rtf=${"%.3f".format(rtf)}")

            return TTSResult(
                pcmData = pcmBytes,
                latencyMs = latencyMs,
                audioDurationSec = audioDurationSec,
                rtf = rtf,
                sampleRate = SAMPLE_RATE,
                isSuccess = true
            )
        } catch (e: Throwable) {
            Log.e(TAG, "TTS synthesis failed for '$text'", e)
            return TTSResult(
                pcmData = ByteArray(0),
                latencyMs = SystemClock.elapsedRealtime() - t0,
                isSuccess = false,
                errorMessage = e.message
            )
        }
    }

    /**
     * Normalizes text via Unicode NFC, maps characters to token IDs,
     * and inserts blank token 0 between every character per VITS architecture.
     */
    fun tokenizeText(rawText: String): LongArray {
        val normalized = Normalizer.normalize(rawText.trim().lowercase(), Normalizer.Form.NFC)
        val tokens = mutableListOf<Long>()

        tokens.add(0L) // Leading blank token
        for (ch in normalized) {
            val key = ch.toString()
            val tokenId = tokenMap[key] ?: (ch.code.toLong()) // Fallback to character code if uninitialized
            tokens.add(tokenId)
            tokens.add(0L) // Interleaved blank token
        }
        return tokens.toLongArray()
    }

    fun textToTokens(rawText: String): LongArray = tokenizeText(rawText)

    override fun release() {
        try {
            ortSession?.close()
            ortSession = null
            _isLoaded = false
            Log.i(TAG, "$modelName released from native RAM")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing $modelName", e)
        }
    }
}
