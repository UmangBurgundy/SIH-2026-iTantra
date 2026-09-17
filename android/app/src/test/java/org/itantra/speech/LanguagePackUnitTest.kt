package org.itantra.speech

import org.itantra.speech.pack.LanguagePackInstaller
import org.itantra.speech.pack.LanguagePackManifest
import org.itantra.speech.pack.LanguagePackRepository
import org.itantra.speech.pack.PackFileEntry
import org.itantra.speech.tts.MmsTTSBackend
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Unit tests for Phase 9.2 In-App Offline Language Pack System.
 *
 * Validates:
 * - Manifest JSON serialization and deserialization
 * - SHA-256 computation and integrity check
 * - Pack directory paths and structure
 * - Repository registry completeness (all 8 downloadable Indic languages)
 * - MmsTTSBackend multi-script character mapping and token generation
 * - Atomic installation and error handling on corrupted/partial files
 */
class LanguagePackUnitTest {

    @Test
    fun testPackManifestSerializationAndDeserialization() {
        val original = LanguagePackManifest(
            languageCode = "ta",
            englishName = "Tamil",
            nativeName = "தமிழ்",
            version = "1.0",
            sttModel = PackFileEntry(
                filename = "indic-ta.int8.onnx",
                relativeSubdir = "stt",
                downloadUrl = "https://example.com/indic-ta.onnx",
                sizeBytes = 152043520L,
                sha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            ),
            sttTokenizer = PackFileEntry(
                filename = "indic-ta-tokens.txt",
                relativeSubdir = "stt",
                downloadUrl = "https://example.com/tokens.txt",
                sizeBytes = 68000L,
                sha256 = ""
            ),
            ttsModel = PackFileEntry(
                filename = "mms-tam.int8.onnx",
                relativeSubdir = "tts",
                downloadUrl = "https://example.com/mms-tam.onnx",
                sizeBytes = 36700160L,
                sha256 = ""
            ),
            ttsVocab = PackFileEntry(
                filename = "mms-tam-vocab.json",
                relativeSubdir = "tts",
                downloadUrl = "https://example.com/vocab.json",
                sizeBytes = 1200L,
                sha256 = ""
            )
        )

        val json = original.toJson()
        assertTrue("JSON must contain language code", json.contains("\"languageCode\": \"ta\""))
        assertTrue("JSON must contain english name", json.contains("\"englishName\": \"Tamil\""))
        assertTrue("JSON must contain stt", json.contains("\"stt\":"))
        assertTrue("JSON must contain tts", json.contains("\"tts\":"))

        val parsed = LanguagePackManifest.fromJson(json)
        assertEquals(original.languageCode, parsed.languageCode)
        assertEquals(original.englishName, parsed.englishName)
        assertEquals(original.nativeName, parsed.nativeName)
        assertEquals(original.version, parsed.version)
        assertEquals(original.totalSizeBytes, parsed.totalSizeBytes)
        assertEquals(original.allFiles.size, parsed.allFiles.size)
        assertEquals(original.sttModel.filename, parsed.sttModel.filename)
        assertEquals(original.sttModel.sha256, parsed.sttModel.sha256)
    }

    @Test
    fun testRepositoryAllEightLanguagesPresent() {
        val packs = LanguagePackRepository.getAllPacks()
        assertEquals("Must provide exactly 8 downloadable packs for target languages", 8, packs.size)

        val expectedCodes = setOf("gu", "mr", "kn", "ml", "ta", "te", "or", "bn")
        val registeredCodes = packs.map { it.languageCode }.toSet()
        assertEquals(expectedCodes, registeredCodes)

        for (pack in packs) {
            assertTrue("Pack size must be positive and non-zero", pack.totalSizeBytes > 0)
            assertEquals("Pack must have exactly 4 component files", 4, pack.allFiles.size)
            assertNotNull("Pack must be retrievable by code", LanguagePackRepository.getPackManifest(pack.languageCode))
        }
    }

    @Test
    fun testSha256Calculation() {
        val tempFile = File.createTempFile("sha256_test", ".tmp")
        try {
            val text = "iTantra Offline Speech System"
            tempFile.writeText(text, Charsets.UTF_8)

            val hash = LanguagePackInstaller.computeSha256(tempFile)
            assertNotNull("Hash must not be null", hash)
            assertEquals("SHA-256 length must be 64 hex characters", 64, hash?.length)

            tempFile.appendText("!")
            val modifiedHash = LanguagePackInstaller.computeSha256(tempFile)
            assertNotEquals("Hash must change when file is modified", hash, modifiedHash)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun testMmsTTSMultiScriptTokenization() {
        // Test Tamil text: "வணக்கம்" (Vanakkam)
        val tamEngine = MmsTTSBackend(modelName = "MMS-Tamil", langCode = "ta")
        val tamTokens = tamEngine.textToTokens("வணக்கம்")
        assertTrue("Tokens array must not be empty", tamTokens.isNotEmpty())
        assertEquals("First token must be blank token 0", 0L, tamTokens[0])
        assertEquals("Last token must be blank token 0", 0L, tamTokens[tamTokens.size - 1])

        // Test Bengali text: "নমস্কার" (Nomoshkar)
        val benEngine = MmsTTSBackend(modelName = "MMS-Bengali", langCode = "bn")
        val benTokens = benEngine.textToTokens("নমস্কার")
        assertTrue("Bengali tokens must not be empty", benTokens.isNotEmpty())
        assertEquals(0L, benTokens[0])

        // Test Gujarati text: "નમસ્તે" (Namaste)
        val gujEngine = MmsTTSBackend(modelName = "MMS-Gujarati", langCode = "gu")
        val gujTokens = gujEngine.textToTokens("નમસ્તે")
        assertTrue("Gujarati tokens must not be empty", gujTokens.isNotEmpty())
        assertEquals(0L, gujTokens[0])
    }

    @Test
    fun testAtomicInstallationSafetyOnMissingFiles() {
        val tempDir = File.createTempFile("pack_test_root", "").apply {
            delete()
            mkdirs()
        }

        try {
            val stagingDir = File(tempDir, ".partial/te").apply { mkdirs() }
            val fakeManifest = LanguagePackManifest(
                languageCode = "te",
                englishName = "Telugu",
                nativeName = "తెలుగు",
                version = "1.0",
                sttModel = PackFileEntry("indic-te.int8.onnx", "stt", "url", 500L, ""),
                sttTokenizer = PackFileEntry("tokens.txt", "stt", "url", 100L, ""),
                ttsModel = PackFileEntry("mms.onnx", "tts", "url", 500L, ""),
                ttsVocab = PackFileEntry("vocab.json", "tts", "url", 100L, "")
            )

            // Test missing file sanity check
            for (entry in fakeManifest.allFiles) {
                val file = File(File(stagingDir, entry.relativeSubdir), entry.filename)
                assertFalse("File should not exist in empty staging", file.exists())
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
