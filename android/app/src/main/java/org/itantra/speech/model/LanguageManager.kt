package org.itantra.speech.model

import org.itantra.speech.stt.IndicSTTBackend
import org.itantra.speech.stt.STTBackend
import org.itantra.speech.stt.SherpaOnnxSTTBackend

/**
 * Manages the 10 target languages for iTantra, maintaining an honest validation status
 * for each language and decoupling backend instantiation from the UI.
 *
 * Architecture:
 * AudioRecord -> DualGateVad -> UtteranceSegmenter -> LanguageManager -> [SherpaOnnxSTTBackend / IndicSTTBackend]
 */
class LanguageManager {

    enum class STTEngineType {
        WHISPER_TINY,      // Validated for English
        INDIC_CONFORMER    // Dedicated for Native Script Indic STT (AI4Bharat IndicConformer CTC)
    }

    enum class LanguageStatus {
        VALIDATED,             // Verified end-to-end on target language with measured WER/CER
        EXPERIMENTAL,          // Runs on target language, undergoing physical validation
        NOT_YET_VALIDATED,     // Pipeline model compatible, but not yet tested on device
        MODEL_NOT_INSTALLED    // On-demand model file not downloaded to local storage
    }

    data class LanguageInfo(
        val code: String,
        val nativeName: String,
        val englishName: String,
        val recommendedEngine: STTEngineType,
        var status: LanguageStatus
    ) {
        override fun toString(): String {
            val statusTag = when (status) {
                LanguageStatus.VALIDATED -> "✓ (Validated)"
                LanguageStatus.EXPERIMENTAL -> "⚡ (Testing)"
                LanguageStatus.NOT_YET_VALIDATED -> "⏳ (Not Validated)"
                LanguageStatus.MODEL_NOT_INSTALLED -> "⬇ (Not Installed)"
            }
            return "$englishName ($nativeName) $statusTag"
        }
    }

    val supportedLanguages: List<LanguageInfo> = listOf(
        LanguageInfo("hi", "हिन्दी", "Hindi", STTEngineType.INDIC_CONFORMER, LanguageStatus.VALIDATED),
        LanguageInfo("en", "English", "English", STTEngineType.WHISPER_TINY, LanguageStatus.VALIDATED),
        LanguageInfo("gu", "ગુજરાતી", "Gujarati", STTEngineType.INDIC_CONFORMER, LanguageStatus.NOT_YET_VALIDATED),
        LanguageInfo("mr", "मराठी", "Marathi", STTEngineType.INDIC_CONFORMER, LanguageStatus.NOT_YET_VALIDATED),
        LanguageInfo("kn", "ಕನ್ನಡ", "Kannada", STTEngineType.INDIC_CONFORMER, LanguageStatus.NOT_YET_VALIDATED),
        LanguageInfo("ml", "മലയാളം", "Malayalam", STTEngineType.INDIC_CONFORMER, LanguageStatus.NOT_YET_VALIDATED),
        LanguageInfo("ta", "தமிழ்", "Tamil", STTEngineType.INDIC_CONFORMER, LanguageStatus.NOT_YET_VALIDATED),
        LanguageInfo("te", "తెలుగు", "Telugu", STTEngineType.INDIC_CONFORMER, LanguageStatus.NOT_YET_VALIDATED),
        LanguageInfo("or", "ଓଡ଼ିଆ", "Odia", STTEngineType.INDIC_CONFORMER, LanguageStatus.NOT_YET_VALIDATED),
        LanguageInfo("bn", "বাংলা", "Bengali", STTEngineType.INDIC_CONFORMER, LanguageStatus.NOT_YET_VALIDATED)
    )

    private var currentLanguage: LanguageInfo = supportedLanguages[0] // Default to Hindi

    val activeLanguage: LanguageInfo get() = currentLanguage

    fun setLanguage(language: LanguageInfo) {
        currentLanguage = language
    }

    fun setLanguageByCode(code: String): Boolean {
        val found = supportedLanguages.find { it.code.equals(code, ignoreCase = true) }
        return if (found != null) {
            currentLanguage = found
            true
        } else {
            false
        }
    }

    /**
     * Factory function: Creates the required STT backend for the selected language.
     * Enforces that only ONE model is active in RAM at a time.
     */
    fun createBackendForLanguage(language: LanguageInfo): STTBackend {
        return when (language.recommendedEngine) {
            STTEngineType.WHISPER_TINY -> SherpaOnnxSTTBackend()
            STTEngineType.INDIC_CONFORMER -> IndicSTTBackend()
        }
    }

    /**
     * Factory function: Creates the required TTS backend for the selected language.
     * Enforces modularity and on-demand model loading (Requirement 7 & 8).
     */
    fun createTTSBackendForLanguage(language: LanguageInfo): org.itantra.speech.tts.TTSBackend {
        return when (language.code) {
            "hi" -> org.itantra.speech.tts.HindiMmsTTSBackend()
            "gu" -> org.itantra.speech.tts.GujaratiMmsTTSBackend()
            "mr" -> org.itantra.speech.tts.MarathiMmsTTSBackend()
            "kn" -> org.itantra.speech.tts.KannadaMmsTTSBackend()
            "ml" -> org.itantra.speech.tts.MalayalamMmsTTSBackend()
            "ta" -> org.itantra.speech.tts.TamilMmsTTSBackend()
            "te" -> org.itantra.speech.tts.TeluguMmsTTSBackend()
            "or" -> org.itantra.speech.tts.OdiaMmsTTSBackend()
            "bn" -> org.itantra.speech.tts.BengaliMmsTTSBackend()
            "en" -> org.itantra.speech.tts.EnglishTTSBackend()
            else -> org.itantra.speech.tts.HindiMmsTTSBackend()
        }
    }
}
