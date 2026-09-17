package org.itantra.speech.pack

import org.json.JSONObject

/**
 * Manifest definitions and metadata for iTantra on-demand offline language packs.
 *
 * Each pack contains all files necessary to run offline STT and TTS on mobile hardware:
 * - STT: AI4Bharat IndicConformer CTC INT8 model + Tokenizer
 * - TTS: Meta MMS-TTS VITS INT8 model + Vocab JSON
 */
data class PackFileEntry(
    val filename: String,
    val relativeSubdir: String, // e.g. "stt" or "tts"
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String
)

data class LanguagePackManifest(
    val languageCode: String,
    val englishName: String,
    val nativeName: String,
    val version: String = "1.0",
    val sttModel: PackFileEntry,
    val sttTokenizer: PackFileEntry,
    val ttsModel: PackFileEntry,
    val ttsVocab: PackFileEntry
) {
    val totalSizeBytes: Long
        get() = sttModel.sizeBytes + sttTokenizer.sizeBytes + ttsModel.sizeBytes + ttsVocab.sizeBytes

    val totalSizeMb: Double
        get() = totalSizeBytes.toDouble() / (1024.0 * 1024.0)

    val allFiles: List<PackFileEntry>
        get() = listOf(sttModel, sttTokenizer, ttsModel, ttsVocab)

    fun toJson(): String {
        val root = JSONObject()
        root.put("languageCode", languageCode)
        root.put("englishName", englishName)
        root.put("nativeName", nativeName)
        root.put("version", version)
        root.put("totalSizeBytes", totalSizeBytes)

        val sttObj = JSONObject()
        sttObj.put("model", fileToJson(sttModel))
        sttObj.put("tokenizer", fileToJson(sttTokenizer))
        root.put("stt", sttObj)

        val ttsObj = JSONObject()
        ttsObj.put("model", fileToJson(ttsModel))
        ttsObj.put("vocab", fileToJson(ttsVocab))
        root.put("tts", ttsObj)

        return root.toString(2)
    }

    private fun fileToJson(entry: PackFileEntry): JSONObject {
        return JSONObject().apply {
            put("filename", entry.filename)
            put("subdir", entry.relativeSubdir)
            put("url", entry.downloadUrl)
            put("sizeBytes", entry.sizeBytes)
            put("sha256", entry.sha256)
        }
    }

    companion object {
        fun fromJson(jsonStr: String): LanguagePackManifest {
            val root = JSONObject(jsonStr)
            val sttObj = root.getJSONObject("stt")
            val ttsObj = root.getJSONObject("tts")

            return LanguagePackManifest(
                languageCode = root.getString("languageCode"),
                englishName = root.getString("englishName"),
                nativeName = root.getString("nativeName"),
                version = root.optString("version", "1.0"),
                sttModel = fileFromJson(sttObj.getJSONObject("model")),
                sttTokenizer = fileFromJson(sttObj.getJSONObject("tokenizer")),
                ttsModel = fileFromJson(ttsObj.getJSONObject("model")),
                ttsVocab = fileFromJson(ttsObj.getJSONObject("vocab"))
            )
        }

        private fun fileFromJson(obj: JSONObject): PackFileEntry {
            return PackFileEntry(
                filename = obj.getString("filename"),
                relativeSubdir = obj.getString("subdir"),
                downloadUrl = obj.getString("url"),
                sizeBytes = obj.getLong("sizeBytes"),
                sha256 = obj.getString("sha256")
            )
        }
    }
}
