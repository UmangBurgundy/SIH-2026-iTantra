package org.itantra.speech.model

import android.content.Context
import org.itantra.speech.pack.LanguagePackRepository
import org.itantra.speech.stt.IndicSTTBackend
import org.itantra.speech.stt.STTBackend
import org.itantra.speech.stt.SherpaOnnxSTTBackend
import org.itantra.speech.tts.*

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
        MODEL_NOT_INSTALLED,   // On-demand model file not downloaded to local storage
        DOWNLOADING,           // Download currently in progress
        INSTALLED              // Downloaded and verified in local persistent storage
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
                LanguageStatus.INSTALLED -> "✓ (Offline Ready)"
                LanguageStatus.DOWNLOADING -> "⬇ (Downloading...)"
                LanguageStatus.EXPERIMENTAL -> "⚡ (Testing)"
                LanguageStatus.NOT_YET_VALIDATED -> "⏳ (Not Validated)"
                LanguageStatus.MODEL_NOT_INSTALLED -> "⬇ (Download)"
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

    fun getLanguageStatus(code: String): LanguageStatus {
        return supportedLanguages.firstOrNull { it.code.equals(code, ignoreCase = true) }?.status
            ?: LanguageStatus.MODEL_NOT_INSTALLED
    }

    fun setLanguageStatus(code: String, status: LanguageStatus) {
        val lang = supportedLanguages.firstOrNull { it.code.equals(code, ignoreCase = true) }
        lang?.status = status
    }

    /**
     * Refreshes installation statuses based on local persistent pack directory.
     */
    fun refreshStatuses(context: Context) {
        for (lang in supportedLanguages) {
            if (lang.code == "hi" || lang.code == "en") {
                lang.status = LanguageStatus.VALIDATED
            } else {
                val isInstalled = LanguagePackRepository.isPackInstalled(context, lang.code)
                if (isInstalled) {
                    lang.status = LanguageStatus.INSTALLED
                } else if (lang.status != LanguageStatus.DOWNLOADING) {
                    lang.status = LanguageStatus.MODEL_NOT_INSTALLED
                }
            }
        }
    }

    /**
     * Factory function: Creates the required STT backend for the selected language.
     * Enforces that only ONE model is active in RAM at a time.
     */
    fun createBackendForLanguage(language: LanguageInfo): STTBackend {
        return when (language.recommendedEngine) {
            STTEngineType.WHISPER_TINY -> SherpaOnnxSTTBackend()
            STTEngineType.INDIC_CONFORMER -> IndicSTTBackend(langCode = language.code)
        }
    }

    /**
     * Factory function: Creates the required TTS backend for the selected language.
     * Enforces modularity and on-demand model loading (Requirement 7 & 8).
     */
    fun createTTSBackendForLanguage(language: LanguageInfo): TTSBackend {
        return when (language.code) {
            "hi" -> HindiMmsTTSBackend()
            "gu" -> GujaratiMmsTTSBackend()
            "mr" -> MarathiMmsTTSBackend()
            "kn" -> KannadaMmsTTSBackend()
            "ml" -> MalayalamMmsTTSBackend()
            "ta" -> TamilMmsTTSBackend()
            "te" -> TeluguMmsTTSBackend()
            "or" -> OdiaMmsTTSBackend()
            "bn" -> BengaliMmsTTSBackend()
            "en" -> EnglishTTSBackend()
            else -> HindiMmsTTSBackend()
        }
    }
}
