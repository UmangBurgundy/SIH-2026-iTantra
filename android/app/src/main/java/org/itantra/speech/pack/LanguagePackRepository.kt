package org.itantra.speech.pack

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import java.io.File

/**
 * Repository managing catalog metadata, installation discovery,
 * and storage space validation for on-demand language packs.
 */
object LanguagePackRepository {

    private const val TAG = "iTantraPackRepo"
    const val PACKS_ROOT_DIR = "language_packs"
    const val PARTIAL_DOWNLOAD_DIR = ".partial"

    // Base download repository URLs for AI4Bharat IndicConformer & Meta MMS-TTS
    private const val HF_SHERPA_BASE = "https://huggingface.co/csukuangfj"
    private const val HF_MMS_BASE = "https://huggingface.co/facebook"

    /**
     * Complete catalogue of the 8 on-demand downloadable language packs.
     * Hindi and English are bundled in APK assets and do not need downloading.
     */
    /**
     * Complete catalogue of the 8 on-demand downloadable language packs.
     * Hindi and English are bundled in APK assets and do not need downloading.
     */
    val downloadablePacks: Map<String, LanguagePackManifest> = listOf(
        createManifest("gu", "Gujarati", "ગુજરાતી", "guj", 195600000L, 38100000L),
        createManifest("mr", "Marathi", "मराठी", "mar", 196200000L, 38400000L),
        createManifest("kn", "Kannada", "ಕನ್ನಡ", "kan", 194800000L, 37900000L),
        createManifest("ml", "Malayalam", "മലയാളം", "mal", 197100000L, 38600000L),
        createManifest("ta", "Tamil", "தமிழ்", "tam", 196800000L, 38200000L),
        createManifest("te", "Telugu", "తెలుగు", "tel", 195900000L, 38300000L),
        createManifest("or", "Odia", "ଓଡ଼ିଆ", "ory", 194500000L, 37800000L),
        createManifest("bn", "Bengali", "বাংলা", "ben", 196500000L, 38500000L)
    ).associateBy { it.languageCode }

    /**
     * Optimized v2.0 language pack catalogue (Phase 9.4: -28.6% STT + graph-optimized TTS).
     */
    val optimizedPacks: Map<String, LanguagePackManifest> = listOf(
        createManifest("gu", "Gujarati", "ગુજરાતી", "guj", 134400000L, 36000000L, version = "2.0"),
        createManifest("mr", "Marathi", "मराठी", "mar", 134600000L, 36000000L, version = "2.0"),
        createManifest("kn", "Kannada", "ಕನ್ನಡ", "kan", 134200000L, 35900000L, version = "2.0"),
        createManifest("ml", "Malayalam", "മലയാളം", "mal", 134800000L, 36100000L, version = "2.0"),
        createManifest("ta", "Tamil", "தமிழ்", "tam", 134700000L, 36000000L, version = "2.0"),
        createManifest("te", "Telugu", "తెలుగు", "tel", 134500000L, 36000000L, version = "2.0"),
        createManifest("or", "Odia", "ଓଡ଼ିଆ", "ory", 134100000L, 35900000L, version = "2.0"),
        createManifest("bn", "Bengali", "বাংলা", "ben", 134600000L, 36100000L, version = "2.0")
    ).associateBy { it.languageCode }

    fun getAllPacks(useOptimized: Boolean = false): List<LanguagePackManifest> =
        if (useOptimized) optimizedPacks.values.toList() else downloadablePacks.values.toList()

    fun getPackManifest(langCode: String, useOptimized: Boolean = false): LanguagePackManifest? =
        if (useOptimized) optimizedPacks[langCode] else downloadablePacks[langCode]

    private fun createManifest(
        code: String,
        englishName: String,
        nativeName: String,
        mmsCode: String,
        sttSizeBytes: Long,
        ttsSizeBytes: Long,
        version: String = "1.0"
    ): LanguagePackManifest {
        return LanguagePackManifest(
            languageCode = code,
            englishName = englishName,
            nativeName = nativeName,
            version = version,
            sttModel = PackFileEntry(
                filename = "indic-$code.int8.onnx",
                relativeSubdir = "stt",
                downloadUrl = "$HF_SHERPA_BASE/sherpa-onnx-nemo-indic-conformer-$code-int8/resolve/main/model.int8.onnx",
                sizeBytes = sttSizeBytes,
                sha256 = "" // Validated on download size & header
            ),
            sttTokenizer = PackFileEntry(
                filename = "indic-$code-tokens.txt",
                relativeSubdir = "stt",
                downloadUrl = "$HF_SHERPA_BASE/sherpa-onnx-nemo-indic-conformer-$code-int8/resolve/main/tokens.txt",
                sizeBytes = 68000L,
                sha256 = ""
            ),
            ttsModel = PackFileEntry(
                filename = "mms-$mmsCode.int8.onnx",
                relativeSubdir = "tts",
                downloadUrl = "$HF_MMS_BASE/mms-tts-$mmsCode/resolve/main/model.onnx",
                sizeBytes = ttsSizeBytes,
                sha256 = ""
            ),
            ttsVocab = PackFileEntry(
                filename = "mms-$mmsCode-vocab.json",
                relativeSubdir = "tts",
                downloadUrl = "$HF_MMS_BASE/mms-tts-$mmsCode/resolve/main/vocab.json",
                sizeBytes = 1200L,
                sha256 = ""
            )
        )
    }

    /**
     * Returns the persistent storage directory for language packs:
     * `/data/data/org.itantra.speech/files/language_packs/`
     */
    fun getPacksRootDir(context: Context): File {
        val dir = File(context.filesDir, PACKS_ROOT_DIR)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Returns the installed directory for a specific language pack, or null if not installed.
     */
    fun getInstalledPackDirectory(context: Context, langCode: String): File? {
        if (langCode == "hi" || langCode == "en") {
            return null // Bundled in assets
        }
        val targetDir = File(getPacksRootDir(context), langCode)
        return if (isPackInstalled(context, langCode)) targetDir else null
    }

    fun getInstalledPackDir(context: Context, langCode: String): File? = getInstalledPackDirectory(context, langCode)

    /**
     * Checks if a language pack is completely installed and verified on disk.
     */
    fun isPackInstalled(context: Context, langCode: String): Boolean {
        if (langCode == "hi" || langCode == "en") return true // Bundled in APK

        val manifest = downloadablePacks[langCode] ?: return false
        val packDir = File(getPacksRootDir(context), langCode)
        if (!packDir.exists() || !packDir.isDirectory) return false

        for (fileEntry in manifest.allFiles) {
            val file = File(File(packDir, fileEntry.relativeSubdir), fileEntry.filename)
            if (!file.exists() || file.length() < 100) {
                return false
            }
        }
        return true
    }

    /**
     * Returns available internal persistent disk space in bytes.
     */
    fun getAvailableDiskSpace(context: Context): Long = getAvailableDiskSpaceBytes(context)

    fun getAvailableDiskSpaceBytes(context: Context): Long {
        return try {
            val stat = StatFs(context.filesDir.absolutePath)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get available disk space", e)
            Long.MAX_VALUE
        }
    }

    /**
     * Checks if device has enough storage to install the requested pack (with a safe 50MB safety buffer).
     */
    fun hasSufficientSpace(context: Context, langCode: String): Boolean {
        val manifest = downloadablePacks[langCode] ?: return false
        return hasSufficientSpace(context, manifest.totalSizeBytes)
    }

    fun hasSufficientSpace(context: Context, requiredBytes: Long): Boolean {
        val available = getAvailableDiskSpaceBytes(context)
        val safetyBuffer = 50L * 1024L * 1024L // 50 MB buffer
        return available > (requiredBytes + safetyBuffer)
    }
}
