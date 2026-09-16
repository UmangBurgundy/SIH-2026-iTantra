package org.itantra.speech.tts

import android.content.Context

/**
 * Modular placeholders for future Indic & English TTS backends.
 *
 * Prepared for future phases (Gujarati, Marathi, Kannada, Malayalam, Tamil, Telugu, Odia, Bengali, English)
 * adhering to Requirement 7 and modular architecture requirements.
 */

open class UnimplementedTTSBackend(
    override val modelName: String,
    val langCode: String
) : TTSBackend {
    override val isLoaded: Boolean get() = false
    override val loadDurationMs: Long get() = 0L
    override val sampleRate: Int get() = 16000

    override fun initialize(context: Context, modelDir: String?): Boolean {
        throw UnsupportedOperationException("TTS for '$langCode' ($modelName) is scheduled for a future phase.")
    }

    override fun synthesize(text: String): TTSResult {
        return TTSResult(
            pcmData = ByteArray(0),
            isSuccess = false,
            errorMessage = "Language '$langCode' TTS not yet validated on mobile."
        )
    }

    override fun release() {}
}

class GujaratiMmsTTSBackend : UnimplementedTTSBackend("Meta MMS-TTS Gujarati (vits-guj)", "gu")
class MarathiMmsTTSBackend : UnimplementedTTSBackend("Meta MMS-TTS Marathi (vits-mar)", "mr")
class KannadaMmsTTSBackend : UnimplementedTTSBackend("Meta MMS-TTS Kannada (vits-kan)", "kn")
class MalayalamMmsTTSBackend : UnimplementedTTSBackend("Meta MMS-TTS Malayalam (vits-mal)", "ml")
class TamilMmsTTSBackend : UnimplementedTTSBackend("Meta MMS-TTS Tamil (vits-tam)", "ta")
class TeluguMmsTTSBackend : UnimplementedTTSBackend("Meta MMS-TTS Telugu (vits-tel)", "te")
class OdiaMmsTTSBackend : UnimplementedTTSBackend("Meta MMS-TTS Odia (vits-ory)", "or")
class BengaliMmsTTSBackend : UnimplementedTTSBackend("Meta MMS-TTS Bengali (vits-ben)", "bn")
class EnglishTTSBackend : UnimplementedTTSBackend("Meta MMS-TTS English (vits-eng)", "en")
